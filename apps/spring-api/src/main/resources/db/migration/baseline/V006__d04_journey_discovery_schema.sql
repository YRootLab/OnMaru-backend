-- onmaru-checksum: d04-v006-20260915
-- Issue: #77 D04 Exploration, run, proposal, saved journey, and saved resource schema.

CREATE TYPE onmaru.discovery_run_status AS ENUM (
    'QUEUED',
    'RUNNING',
    'COMPLETED',
    'FAILED',
    'CANCELLED'
);

CREATE TYPE onmaru.discovery_proposal_status AS ENUM (
    'PENDING',
    'APPLIED',
    'DISMISSED',
    'INVALIDATED'
);

CREATE TYPE onmaru.journey_saved_resource_type AS ENUM (
    'PLACE',
    'ODII_STORY'
);

CREATE TABLE onmaru.discovery_explorations (
    id uuid PRIMARY KEY,
    owner_member_id uuid REFERENCES onmaru.identity_members (id),
    owner_guest_id uuid REFERENCES onmaru.identity_guests (id),
    state_version integer NOT NULL,
    board jsonb,
    pinned_refs jsonb NOT NULL,
    excluded_refs jsonb NOT NULL,
    region_id uuid REFERENCES onmaru.catalog_regions (id),
    expires_at timestamptz,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT discovery_explorations_owner_xor_ck CHECK (
        (owner_member_id IS NULL) <> (owner_guest_id IS NULL)
    ),
    CONSTRAINT discovery_explorations_state_version_ck CHECK (state_version >= 0),
    CONSTRAINT discovery_explorations_pinned_refs_array_ck CHECK (jsonb_typeof(pinned_refs) = 'array'),
    CONSTRAINT discovery_explorations_excluded_refs_array_ck CHECK (jsonb_typeof(excluded_refs) = 'array'),
    CONSTRAINT discovery_explorations_board_object_ck CHECK (
        board IS NULL OR jsonb_typeof(board) = 'object'
    ),
    CONSTRAINT discovery_explorations_updated_at_ck CHECK (updated_at >= created_at)
);

CREATE INDEX discovery_explorations_owner_member_updated_idx
    ON onmaru.discovery_explorations (owner_member_id, updated_at);
CREATE INDEX discovery_explorations_owner_guest_updated_idx
    ON onmaru.discovery_explorations (owner_guest_id, updated_at);

CREATE TABLE onmaru.discovery_runs (
    id uuid PRIMARY KEY,
    exploration_id uuid NOT NULL REFERENCES onmaru.discovery_explorations (id),
    actor_key varchar NOT NULL,
    base_version integer NOT NULL,
    status onmaru.discovery_run_status NOT NULL,
    stage varchar,
    outcome varchar,
    clarification jsonb,
    created_at timestamptz NOT NULL,
    deadline_at timestamptz NOT NULL,
    started_at timestamptz,
    generation integer NOT NULL,
    error_code varchar,
    engine varchar NOT NULL,
    CONSTRAINT discovery_runs_base_version_ck CHECK (base_version >= 0),
    CONSTRAINT discovery_runs_actor_key_ck CHECK (btrim(actor_key) <> ''),
    CONSTRAINT discovery_runs_generation_ck CHECK (generation > 0),
    CONSTRAINT discovery_runs_deadline_ck CHECK (deadline_at > created_at),
    CONSTRAINT discovery_runs_engine_ck CHECK (btrim(engine) <> ''),
    CONSTRAINT discovery_runs_terminal_outcome_ck CHECK (
        (status IN ('QUEUED', 'RUNNING') AND outcome IS NULL)
        OR (status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND outcome IS NOT NULL)
    ),
    CONSTRAINT discovery_runs_clarification_object_ck CHECK (
        clarification IS NULL OR jsonb_typeof(clarification) = 'object'
    )
);

CREATE INDEX discovery_runs_exploration_id_idx
    ON onmaru.discovery_runs (exploration_id);
CREATE INDEX discovery_runs_actor_key_idx
    ON onmaru.discovery_runs (actor_key);
CREATE INDEX discovery_runs_deadline_at_idx
    ON onmaru.discovery_runs (deadline_at);
CREATE UNIQUE INDEX discovery_runs_active_exploration_uq
    ON onmaru.discovery_runs (exploration_id)
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE UNIQUE INDEX discovery_runs_active_actor_uq
    ON onmaru.discovery_runs (actor_key)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE onmaru.discovery_proposals (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL UNIQUE REFERENCES onmaru.discovery_runs (id),
    exploration_id uuid NOT NULL REFERENCES onmaru.discovery_explorations (id),
    base_version integer NOT NULL,
    ordered_refs jsonb NOT NULL,
    reasons jsonb NOT NULL,
    evidence jsonb NOT NULL,
    expires_at timestamptz NOT NULL,
    status onmaru.discovery_proposal_status NOT NULL,
    CONSTRAINT discovery_proposals_base_version_ck CHECK (base_version >= 0),
    CONSTRAINT discovery_proposals_ordered_refs_array_ck CHECK (jsonb_typeof(ordered_refs) = 'array'),
    CONSTRAINT discovery_proposals_reasons_object_ck CHECK (jsonb_typeof(reasons) = 'object'),
    CONSTRAINT discovery_proposals_evidence_object_ck CHECK (jsonb_typeof(evidence) = 'object')
);

CREATE INDEX discovery_proposals_exploration_status_idx
    ON onmaru.discovery_proposals (exploration_id, status);

CREATE TABLE onmaru.discovery_turns (
    id uuid PRIMARY KEY,
    exploration_id uuid NOT NULL REFERENCES onmaru.discovery_explorations (id),
    client_turn_id uuid NOT NULL,
    query text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT discovery_turns_query_ck CHECK (btrim(query) <> ''),
    CONSTRAINT discovery_turns_exploration_client_turn_uq UNIQUE (exploration_id, client_turn_id)
);

CREATE INDEX discovery_turns_exploration_created_at_idx
    ON onmaru.discovery_turns (exploration_id, created_at);

CREATE TABLE onmaru.journey_saved_journeys (
    id uuid PRIMARY KEY,
    member_id uuid NOT NULL REFERENCES onmaru.identity_members (id),
    source_exploration_id uuid REFERENCES onmaru.discovery_explorations (id) ON DELETE SET NULL,
    source_version integer NOT NULL,
    saved_at timestamptz NOT NULL,
    title varchar NOT NULL,
    snapshot jsonb NOT NULL,
    snapshot_hash varchar NOT NULL,
    CONSTRAINT journey_saved_journeys_source_version_ck CHECK (source_version >= 0),
    CONSTRAINT journey_saved_journeys_title_ck CHECK (btrim(title) <> ''),
    CONSTRAINT journey_saved_journeys_snapshot_object_ck CHECK (jsonb_typeof(snapshot) = 'object'),
    CONSTRAINT journey_saved_journeys_snapshot_hash_ck CHECK (btrim(snapshot_hash) <> ''),
    CONSTRAINT journey_saved_journeys_member_source_version_uq
        UNIQUE (member_id, source_exploration_id, source_version)
);

CREATE INDEX journey_saved_journeys_member_saved_at_idx
    ON onmaru.journey_saved_journeys (member_id, saved_at, id);

CREATE TABLE onmaru.journey_saved_resources (
    id uuid PRIMARY KEY,
    member_id uuid NOT NULL REFERENCES onmaru.identity_members (id),
    resource_type onmaru.journey_saved_resource_type NOT NULL,
    resource_id uuid NOT NULL,
    saved_at timestamptz NOT NULL,
    CONSTRAINT journey_saved_resources_member_resource_uq
        UNIQUE (member_id, resource_type, resource_id)
);

CREATE INDEX journey_saved_resources_member_type_saved_at_idx
    ON onmaru.journey_saved_resources (member_id, resource_type, saved_at, id);

ALTER TABLE onmaru.identity_oauth_states
    ADD CONSTRAINT identity_oauth_states_exploration_id_fkey
    FOREIGN KEY (exploration_id) REFERENCES onmaru.discovery_explorations (id);

ALTER TABLE onmaru.identity_exploration_grants
    ADD CONSTRAINT identity_exploration_grants_exploration_id_fkey
    FOREIGN KEY (exploration_id) REFERENCES onmaru.discovery_explorations (id);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '006',
    'D04',
    77,
    'Discovery exploration, run, proposal, turn, saved journey, and saved resource schema'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

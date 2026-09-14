-- onmaru-checksum: d07-v004-20260915
-- Issue: #80 D07 Sync run, lease, checkpoint, quarantine, and outbox schema.

CREATE TYPE onmaru.operations_sync_run_status AS ENUM (
    'QUEUED',
    'RUNNING',
    'SUCCEEDED',
    'FAILED',
    'ABANDONED'
);

CREATE TABLE onmaru.operations_sync_schedules (
    dataset varchar PRIMARY KEY,
    timezone varchar NOT NULL,
    local_time varchar NOT NULL,
    enabled boolean NOT NULL,
    next_due_at timestamptz NOT NULL,
    CONSTRAINT operations_sync_schedules_local_time_ck CHECK (
        local_time ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
    )
);

CREATE INDEX operations_sync_schedules_next_due_at_idx
    ON onmaru.operations_sync_schedules (next_due_at)
    WHERE enabled;

CREATE TABLE onmaru.operations_sync_runs (
    id uuid PRIMARY KEY,
    dataset varchar NOT NULL,
    scheduled_for timestamptz NOT NULL,
    attempt integer NOT NULL,
    status onmaru.operations_sync_run_status NOT NULL,
    revision_id uuid REFERENCES onmaru.catalog_dataset_revisions (id),
    started_at timestamptz,
    finished_at timestamptz,
    next_attempt_at timestamptz,
    error_code varchar,
    counts jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT operations_sync_runs_attempt_ck CHECK (attempt > 0),
    CONSTRAINT operations_sync_runs_counts_object_ck CHECK (jsonb_typeof(counts) = 'object'),
    CONSTRAINT operations_sync_runs_terminal_finished_at_ck CHECK (
        status NOT IN ('SUCCEEDED', 'FAILED', 'ABANDONED') OR finished_at IS NOT NULL
    ),
    CONSTRAINT operations_sync_runs_dataset_scheduled_for_attempt_uq
        UNIQUE (dataset, scheduled_for, attempt)
);

CREATE INDEX operations_sync_runs_dataset_status_idx
    ON onmaru.operations_sync_runs (dataset, status);
CREATE INDEX operations_sync_runs_revision_id_idx
    ON onmaru.operations_sync_runs (revision_id);

CREATE TABLE onmaru.operations_sync_checkpoints (
    run_id uuid NOT NULL REFERENCES onmaru.operations_sync_runs (id),
    partition_key varchar NOT NULL,
    next_page integer,
    source_cursor varchar,
    expected_total integer,
    seen_count integer NOT NULL,
    last_source_modified varchar,
    last_external_id varchar,
    PRIMARY KEY (run_id, partition_key),
    CONSTRAINT operations_sync_checkpoints_next_page_ck CHECK (
        next_page IS NULL OR next_page > 0
    ),
    CONSTRAINT operations_sync_checkpoints_expected_total_ck CHECK (
        expected_total IS NULL OR expected_total >= 0
    ),
    CONSTRAINT operations_sync_checkpoints_seen_count_ck CHECK (seen_count >= 0)
);

CREATE TABLE onmaru.operations_sync_leases (
    dataset varchar PRIMARY KEY,
    owner_token varchar NOT NULL,
    generation integer NOT NULL,
    lease_until timestamptz NOT NULL,
    CONSTRAINT operations_sync_leases_generation_ck CHECK (generation > 0)
);

CREATE INDEX operations_sync_leases_lease_until_idx
    ON onmaru.operations_sync_leases (lease_until);

CREATE TABLE onmaru.operations_sync_watermarks (
    dataset varchar PRIMARY KEY,
    source_modified_at varchar,
    external_id varchar,
    last_full_success_at timestamptz,
    last_success_at timestamptz,
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id)
);

CREATE TABLE onmaru.operations_sync_quarantine (
    run_id uuid NOT NULL REFERENCES onmaru.operations_sync_runs (id),
    record_key varchar NOT NULL,
    error_code varchar NOT NULL,
    payload_hash varchar NOT NULL,
    redacted_payload jsonb,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (run_id, record_key),
    CONSTRAINT operations_sync_quarantine_payload_hash_ck CHECK (
        payload_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX operations_sync_quarantine_expires_at_idx
    ON onmaru.operations_sync_quarantine (expires_at);

CREATE TABLE onmaru.operations_outbox_events (
    event_id uuid PRIMARY KEY,
    document_id varchar NOT NULL,
    revision varchar NOT NULL,
    event_type varchar NOT NULL,
    payload jsonb NOT NULL,
    available_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    delivered_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT operations_outbox_events_attempts_ck CHECK (attempts >= 0),
    CONSTRAINT operations_outbox_events_payload_object_ck CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX operations_outbox_events_undelivered_idx
    ON onmaru.operations_outbox_events (available_at, event_id)
    WHERE delivered_at IS NULL;

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '004',
    'D07',
    80,
    'Sync operations schedules, runs, leases, checkpoints, watermarks, quarantine, and outbox schema'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

-- onmaru-checksum: journey-exploration-persistence-v053-20261105
-- Issue #783: feat(ai): 여정 대화·turn 영속화 및 공개 API 계약 구현

ALTER TABLE onmaru.discovery_explorations
    ADD COLUMN IF NOT EXISTS title varchar(100),
    ADD COLUMN IF NOT EXISTS last_user_query_preview varchar(160);

ALTER TABLE onmaru.discovery_turns
    ADD COLUMN IF NOT EXISTS run_id uuid REFERENCES onmaru.discovery_runs (id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS base_version integer DEFAULT 0 NOT NULL,
    ADD COLUMN IF NOT EXISTS region_code varchar,
    ADD COLUMN IF NOT EXISTS clarification_id varchar,
    ADD COLUMN IF NOT EXISTS turn_response jsonb;

CREATE INDEX IF NOT EXISTS discovery_turns_exploration_created_desc_idx
    ON onmaru.discovery_turns (exploration_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS discovery_explorations_active_member_idx
    ON onmaru.discovery_explorations (owner_member_id, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS discovery_explorations_active_guest_idx
    ON onmaru.discovery_explorations (owner_guest_id, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS discovery_explorations_expires_at_idx
    ON onmaru.discovery_explorations (expires_at)
    WHERE expires_at IS NOT NULL AND deleted_at IS NULL;

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '053',
    'JOURNEY_EXPLORATION_PERSISTENCE',
    783,
    'Exploration conversation state, turns persistence, and API 1.3 retention metadata'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

-- onmaru-checksum: journey-run-ai-progress-v055-20261109
-- Issue #809: durable run/plan binding and AI progress ordinal projection

CREATE TABLE IF NOT EXISTS onmaru.journey_run_ai_bindings (
    run_id uuid PRIMARY KEY REFERENCES onmaru.discovery_runs (id) ON DELETE CASCADE,
    plan_hash varchar(80) NOT NULL,
    last_ordinal integer NOT NULL DEFAULT 0,
    last_stage varchar(32),
    bound_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT journey_run_ai_bindings_plan_hash_ck CHECK (plan_hash ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT journey_run_ai_bindings_ordinal_ck CHECK (last_ordinal >= 0),
    CONSTRAINT journey_run_ai_bindings_stage_ck CHECK (
        last_stage IS NULL OR last_stage IN ('INTERPRETING', 'RETRIEVING', 'VALIDATING', 'PERSISTING')
    ),
    CONSTRAINT journey_run_ai_bindings_expiry_ck CHECK (expires_at > bound_at)
);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '055',
    'JOURNEY_RUN_AI_PROGRESS',
    809,
    'Run-bound approved plan binding and durable AI progress ordinal projection'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

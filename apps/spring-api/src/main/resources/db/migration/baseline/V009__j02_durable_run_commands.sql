-- onmaru-checksum: j02-v009-20260916
-- Issue: #109 J02 durable run state machine and command idempotency.

ALTER TABLE onmaru.discovery_runs
    DROP CONSTRAINT discovery_runs_terminal_outcome_ck;

-- V006 required a terminal outcome for every terminal status. Preserve the
-- legacy failure/cancellation reason while moving those statuses to the
-- canonical error_code representation before validating the new constraint.
UPDATE onmaru.discovery_runs
SET error_code = COALESCE(error_code, outcome),
    outcome = NULL
WHERE status IN ('FAILED', 'CANCELLED');

ALTER TABLE onmaru.discovery_runs
    ADD CONSTRAINT discovery_runs_status_outcome_ck CHECK (
        (status IN ('QUEUED', 'RUNNING') AND outcome IS NULL)
        OR (status = 'COMPLETED' AND outcome IS NOT NULL)
        OR (status IN ('FAILED', 'CANCELLED') AND outcome IS NULL)
    ),
    ADD CONSTRAINT discovery_runs_stage_ck CHECK (
        stage IS NULL OR stage IN ('INTERPRETING', 'RETRIEVING', 'VALIDATING', 'PERSISTING')
    );

CREATE TABLE onmaru.discovery_run_commands (
    actor_key varchar NOT NULL,
    operation varchar NOT NULL,
    command_key uuid NOT NULL,
    request_hash varchar NOT NULL,
    run_id uuid NOT NULL REFERENCES onmaru.discovery_runs (id),
    result_status onmaru.discovery_run_status NOT NULL,
    result_stage varchar,
    result_outcome varchar,
    result_generation integer NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (actor_key, operation, command_key),
    CONSTRAINT discovery_run_commands_actor_key_ck CHECK (btrim(actor_key) <> ''),
    CONSTRAINT discovery_run_commands_operation_ck CHECK (
        operation IN ('CREATE', 'CLAIM', 'ADVANCE_STAGE', 'FINISH')
    ),
    CONSTRAINT discovery_run_commands_request_hash_ck CHECK (btrim(request_hash) <> ''),
    CONSTRAINT discovery_run_commands_result_stage_ck CHECK (
        result_stage IS NULL OR result_stage IN ('INTERPRETING', 'RETRIEVING', 'VALIDATING', 'PERSISTING')
    ),
    CONSTRAINT discovery_run_commands_generation_ck CHECK (result_generation > 0),
    CONSTRAINT discovery_run_commands_expiry_ck CHECK (expires_at > created_at)
);

CREATE INDEX discovery_run_commands_expires_at_idx
    ON onmaru.discovery_run_commands (expires_at);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '009',
    'J02',
    109,
    'Durable journey run command idempotency receipts and state constraints'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

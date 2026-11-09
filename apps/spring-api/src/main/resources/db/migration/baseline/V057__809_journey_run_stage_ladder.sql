-- onmaru-checksum: journey-run-stage-ladder-v057-20261109
-- Issue #809/#811: public journey stage ladder aligned with the OpenAPI/SSE 1.3 enum (adds SELECTING_EVIDENCE, GENERATING)

ALTER TABLE onmaru.discovery_runs DROP CONSTRAINT IF EXISTS discovery_runs_stage_ck;
ALTER TABLE onmaru.discovery_runs ADD CONSTRAINT discovery_runs_stage_ck CHECK (
    stage IS NULL OR stage IN ('INTERPRETING', 'RETRIEVING', 'SELECTING_EVIDENCE', 'GENERATING', 'VALIDATING', 'PERSISTING')
);

ALTER TABLE onmaru.discovery_run_commands DROP CONSTRAINT IF EXISTS discovery_run_commands_result_stage_ck;
ALTER TABLE onmaru.discovery_run_commands ADD CONSTRAINT discovery_run_commands_result_stage_ck CHECK (
    result_stage IS NULL OR result_stage IN ('INTERPRETING', 'RETRIEVING', 'SELECTING_EVIDENCE', 'GENERATING', 'VALIDATING', 'PERSISTING')
);

ALTER TABLE onmaru.journey_run_ai_bindings DROP CONSTRAINT IF EXISTS journey_run_ai_bindings_stage_ck;
ALTER TABLE onmaru.journey_run_ai_bindings ADD CONSTRAINT journey_run_ai_bindings_stage_ck CHECK (
    last_stage IS NULL OR last_stage IN ('INTERPRETING', 'RETRIEVING', 'SELECTING_EVIDENCE', 'GENERATING', 'VALIDATING', 'PERSISTING')
);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '057',
    'JOURNEY_RUN_STAGE_LADDER',
    809,
    'Public journey stage ladder aligned with OpenAPI SSE 1.3 stage enum'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

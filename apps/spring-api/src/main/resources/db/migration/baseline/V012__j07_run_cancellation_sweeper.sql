-- onmaru-checksum: j07-v012-20260917
-- Issue: #121 J07 Run cancel, deadline, and sweeper.

ALTER TABLE onmaru.discovery_runs
    ADD COLUMN lease_expires_at timestamptz;

CREATE INDEX discovery_runs_active_lease_expires_at_idx
    ON onmaru.discovery_runs (lease_expires_at)
    WHERE status = 'RUNNING';

ALTER TABLE onmaru.discovery_run_commands
    DROP CONSTRAINT discovery_run_commands_operation_ck,
    ADD CONSTRAINT discovery_run_commands_operation_ck CHECK (
        operation IN ('CREATE', 'CLAIM', 'ADVANCE_STAGE', 'FINISH', 'CANCEL')
    );

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '012',
    'J07',
    121,
    'Journey run cancellation, lease expiry, and deadline sweeper'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

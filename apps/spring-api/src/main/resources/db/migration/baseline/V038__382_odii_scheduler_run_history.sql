-- onmaru-checksum: odii-scheduler-run-history-v038-20261002
-- Issue: #382 Odii scheduler lifecycle without changing existing sync status values.

ALTER TABLE onmaru.operations_sync_runs
    ADD COLUMN trigger_source varchar(32),
    ADD COLUMN lifecycle_status varchar(16),
    ADD COLUMN failure_phase varchar(32),
    ADD COLUMN lease_generation integer,
    ADD CONSTRAINT operations_sync_runs_scheduler_lifecycle_ck CHECK (
        lifecycle_status IS NULL OR (
            trigger_source IS NOT NULL
            AND trigger_source IN ('application-ready', 'scheduled-cron', 'manual')
            AND started_at IS NOT NULL
            AND CASE lifecycle_status
                WHEN 'STARTED' THEN status = 'RUNNING' AND finished_at IS NULL
                    AND failure_phase IS NULL AND error_code IS NULL
                WHEN 'COMPLETED' THEN status = 'SUCCEEDED' AND finished_at IS NOT NULL
                    AND failure_phase IS NULL AND error_code IS NULL
                WHEN 'FAILED' THEN status = 'FAILED' AND finished_at IS NOT NULL
                    AND failure_phase IS NOT NULL AND error_code IS NOT NULL
                WHEN 'SKIPPED' THEN status = 'ABANDONED' AND finished_at IS NOT NULL
                    AND failure_phase IS NOT NULL AND error_code IS NOT NULL
                ELSE false
            END
        )
    ),
    ADD CONSTRAINT operations_sync_runs_scheduler_phase_ck CHECK (
        lifecycle_status IS NULL OR failure_phase IS NULL OR
        failure_phase IN ('DEPENDENCY_CHECK', 'INITIALIZE', 'LEASE', 'SYNC', 'FETCH', 'MAP', 'STAGE', 'PUBLISH')
    ),
    ADD CONSTRAINT operations_sync_runs_scheduler_code_ck CHECK (
        lifecycle_status IS NULL OR error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    ADD CONSTRAINT operations_sync_runs_lease_generation_ck CHECK (
        lease_generation IS NULL OR lease_generation > 0
    );

COMMENT ON COLUMN onmaru.operations_sync_runs.lifecycle_status IS
    'Scheduler STARTED/COMPLETED/FAILED/SKIPPED; legacy jobs keep NULL and existing status enum unchanged';
COMMENT ON COLUMN onmaru.operations_sync_runs.failure_phase IS
    'Internal phase only; exception messages, provider responses and credentials are never stored';

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '038', 'ODII_SCHEDULER_RUN_HISTORY', 382,
    'Odii scheduler trigger, lifecycle, safe failure phase and lease generation history'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

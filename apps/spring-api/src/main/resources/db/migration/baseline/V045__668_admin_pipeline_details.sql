-- onmaru-checksum: admin-pipeline-details-v045-20261006
-- Issue: #668 Admin pipeline run details and sanitized failure log.

ALTER TABLE onmaru.operations_sync_runs
    ADD COLUMN scope varchar(16) NOT NULL DEFAULT 'ALL',
    ADD COLUMN requested_at timestamptz,
    ADD COLUMN current_stage varchar(64),
    ADD COLUMN progress_completed bigint,
    ADD COLUMN progress_total bigint,
    ADD CONSTRAINT operations_sync_runs_scope_ck CHECK (scope IN ('ALL', 'VILLAGES', 'STAYS', 'ROUTES')),
    ADD CONSTRAINT operations_sync_runs_progress_ck CHECK (
        (progress_completed IS NULL OR progress_completed >= 0)
        AND (progress_total IS NULL OR progress_total >= 0)
        AND (progress_completed IS NULL OR progress_total IS NULL OR progress_completed <= progress_total)
    );

UPDATE onmaru.operations_sync_runs
SET requested_at = scheduled_for
WHERE requested_at IS NULL;

CREATE INDEX operations_sync_runs_dataset_latest_idx
    ON onmaru.operations_sync_runs (dataset, COALESCE(started_at, scheduled_for) DESC, id DESC);

CREATE TABLE onmaru.operations_sync_failures (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES onmaru.operations_sync_runs (id) ON DELETE CASCADE,
    occurred_at timestamptz NOT NULL,
    endpoint varchar(120),
    content_id varchar(160),
    error_code varchar(64) NOT NULL,
    message varchar(500) NOT NULL,
    retryable boolean NOT NULL,
    CONSTRAINT operations_sync_failures_error_code_ck CHECK (error_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
);

CREATE INDEX operations_sync_failures_run_page_idx
    ON onmaru.operations_sync_failures (run_id, occurred_at DESC, id DESC);

COMMENT ON TABLE onmaru.operations_sync_failures IS
    'Sanitized admin diagnostics only; credentials, authorization headers, personal data and full upstream payloads are prohibited';

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '045', 'ADMIN_PIPELINE_DETAILS', 668,
    'Admin pipeline run scope, progress snapshot, latest-run index and sanitized failures'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

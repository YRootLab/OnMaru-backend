-- onmaru-checksum: journey-run-degraded-retry-after-v059-20261120
-- Align discovery_runs terminal metadata with RunSnapshot/SSE retryAfterMs and degradedReason contract

ALTER TABLE onmaru.discovery_runs
    ADD COLUMN IF NOT EXISTS degraded_reason varchar,
    ADD COLUMN IF NOT EXISTS retry_after_ms bigint NOT NULL DEFAULT 0;

ALTER TABLE onmaru.discovery_runs DROP CONSTRAINT IF EXISTS discovery_runs_retry_after_ms_ck;
ALTER TABLE onmaru.discovery_runs
    ADD CONSTRAINT discovery_runs_retry_after_ms_ck CHECK (retry_after_ms >= 0);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '059',
    'JOURNEY_RUN_DEGRADED_RETRY_AFTER',
    874,
    'Persist degraded_reason and retry_after_ms on discovery_runs for quota fallback and explicit failure reporting'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

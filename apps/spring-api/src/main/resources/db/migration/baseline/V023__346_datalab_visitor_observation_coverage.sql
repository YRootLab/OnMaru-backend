-- ADR-0015: preserve DataLab coverage semantics; a missing source value is not zero.
ALTER TABLE onmaru.insights_visitor_observations
    ALTER COLUMN visitor_count DROP NOT NULL,
    ADD COLUMN coverage_status varchar NOT NULL DEFAULT 'COMPLETE',
    ADD CONSTRAINT insights_visitor_observations_coverage_status_ck CHECK (
        coverage_status IN ('COMPLETE', 'PARTIAL', 'NOT_AVAILABLE', 'STALE')
    );

CREATE INDEX insights_visitor_observations_active_lookup_idx
    ON onmaru.insights_visitor_observations (revision_id, region_id, basis_date DESC, fetched_at DESC)
    WHERE coverage_status = 'COMPLETE' AND visitor_count IS NOT NULL;

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '023', 'FE-346', 346,
    'DataLab visitor observation coverage status and nullable missing count'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

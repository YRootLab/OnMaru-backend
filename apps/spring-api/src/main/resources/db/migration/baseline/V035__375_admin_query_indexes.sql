-- onmaru-checksum: admin-query-indexes-v035-20260929
-- Issue: #375 Admin query/index optimization.

CREATE INDEX catalog_admin_curation_latest_idx
    ON onmaru.catalog_admin_curation_overrides
       (canonical_place_id, category, version DESC, updated_at DESC);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '035',
    'ADMIN_QUERY_INDEXES',
    375,
    'Latest-version curation lookup index for admin API'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

-- onmaru-checksum: tourapi-v44-legal-district-v031-20260927
-- Issue: #375 TourAPI v4.4 legal-district fields for the complete Korean catalog snapshot.

ALTER TABLE onmaru.catalog_kto_korean_content_versions
    ADD COLUMN ldong_regn_cd varchar,
    ADD COLUMN ldong_signgu_cd varchar;

CREATE INDEX catalog_kto_korean_content_versions_ldong_idx
    ON onmaru.catalog_kto_korean_content_versions (ldong_regn_cd, ldong_signgu_cd);

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '031', 'TOURAPI_V44_CATALOG', 375,
    'TourAPI v4.4 legal district codes for complete Korean catalog staging'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

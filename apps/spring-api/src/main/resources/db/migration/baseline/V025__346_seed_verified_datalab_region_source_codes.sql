-- Official source: https://www.data.go.kr/data/15101972/openapi.do
-- Verification: TourAPI Guide (tourism big data) v4.1 and authenticated nationwide
-- DataLab responses observed on 2026-09-26 for basis date 2025-09-20.
-- DataLab codes are not legal-dong codes: for example, Jeonbuk is SIDO:52, not kr-45.
-- Catalog rows are often imported after Flyway has run. The trigger records these
-- verified mappings at the time each matching active Catalog region is persisted.

CREATE OR REPLACE FUNCTION onmaru.register_verified_datalab_visitor_region_source_codes()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    WITH verified_mappings (region_code, source_code) AS (
        VALUES
            ('kr-11', 'SIDO:11'),
            ('kr-11-jongno', 'SIGUNGU:11110'),
            ('kr-45', 'SIDO:52'),
            ('kr-45-jeonju', 'SIGUNGU:52110')
    )
    INSERT INTO onmaru.catalog_region_source_codes (
        provider, dataset, source_code, valid_from, valid_to, region_id
    )
    SELECT
        'KTO_DATALAB', 'visitor', mapping.source_code,
        DATE '2026-09-26', NULL, region.id
    FROM verified_mappings mapping
    JOIN onmaru.catalog_regions region
      ON region.code = mapping.region_code
     AND region.active
    ON CONFLICT (provider, dataset, source_code, valid_from) DO NOTHING;

    WITH verified_mappings (region_code, source_code) AS (
        VALUES
            ('kr-11', 'SIDO:11'),
            ('kr-11-jongno', 'SIGUNGU:11110'),
            ('kr-45', 'SIDO:52'),
            ('kr-45-jeonju', 'SIGUNGU:52110')
    )
    INSERT INTO onmaru.catalog_region_source_code_verifications (
        provider, dataset, source_code, valid_from,
        official_source_url, verified_at, verified_by
    )
    SELECT
        source.provider, source.dataset, source.source_code, source.valid_from,
        'https://www.data.go.kr/data/15101972/openapi.do',
        TIMESTAMPTZ '2026-09-26 09:00:00+09',
        'onmaru-catalog-data-verification'
    FROM onmaru.catalog_region_source_codes source
    JOIN onmaru.catalog_regions region ON region.id = source.region_id
    JOIN verified_mappings mapping
      ON mapping.region_code = region.code
     AND mapping.source_code = source.source_code
    WHERE source.provider = 'KTO_DATALAB'
      AND source.dataset = 'visitor'
      AND source.valid_from = DATE '2026-09-26'
    ON CONFLICT (provider, dataset, source_code, valid_from) DO NOTHING;
END;
$$;

CREATE OR REPLACE FUNCTION onmaru.register_verified_datalab_visitor_region_source_codes_trigger()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM onmaru.register_verified_datalab_visitor_region_source_codes();
    RETURN NEW;
END;
$$;

CREATE TRIGGER catalog_regions_register_verified_datalab_visitor_source_codes
AFTER INSERT OR UPDATE OF code, active ON onmaru.catalog_regions
FOR EACH ROW
EXECUTE FUNCTION onmaru.register_verified_datalab_visitor_region_source_codes_trigger();

SELECT onmaru.register_verified_datalab_visitor_region_source_codes();

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '025', 'FE-346', 346,
    'Verified DataLab visitor region source-code seed for active Catalog regions'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

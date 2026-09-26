-- Issue #399: auditable, fail-closed DataLab region mapping registry.
CREATE TYPE onmaru.datalab_region_mapping_status AS ENUM ('PENDING', 'ACTIVE', 'REJECTED');

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE onmaru.catalog_datalab_region_mappings (
    provider varchar NOT NULL DEFAULT 'KTO_DATALAB',
    dataset varchar NOT NULL DEFAULT 'visitor',
    source_code varchar NOT NULL,
    valid_from date NOT NULL,
    valid_to date,
    region_id uuid NOT NULL REFERENCES onmaru.catalog_regions (id),
    level onmaru.catalog_region_level NOT NULL,
    name varchar NOT NULL,
    source_url text,
    source_observed_at timestamptz,
    verified_by varchar,
    verified_at timestamptz,
    status onmaru.datalab_region_mapping_status NOT NULL,
    PRIMARY KEY (provider, dataset, source_code, valid_from),
    CONSTRAINT catalog_datalab_region_mappings_mapping_fk
        FOREIGN KEY (provider, dataset, source_code, valid_from)
        REFERENCES onmaru.catalog_region_source_codes (provider, dataset, source_code, valid_from),
    CONSTRAINT catalog_datalab_region_mappings_region_period_excl EXCLUDE USING gist (
        region_id WITH =,
        daterange(valid_from, COALESCE(valid_to, 'infinity'::date), '[]') WITH &&
    ),
    CONSTRAINT catalog_datalab_region_mappings_source_period_excl EXCLUDE USING gist (
        source_code WITH =,
        daterange(valid_from, COALESCE(valid_to, 'infinity'::date), '[]') WITH &&
    ),
    CONSTRAINT catalog_datalab_region_mappings_provider_ck CHECK (provider = 'KTO_DATALAB'),
    CONSTRAINT catalog_datalab_region_mappings_dataset_ck CHECK (dataset = 'visitor'),
    CONSTRAINT catalog_datalab_region_mappings_source_code_ck
        CHECK (source_code ~ '^(SIDO|SIGUNGU):[^:[:space:]]+$'),
    CONSTRAINT catalog_datalab_region_mappings_name_ck CHECK (btrim(name) <> ''),
    CONSTRAINT catalog_datalab_region_mappings_validity_ck
        CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT catalog_datalab_region_mappings_source_url_ck
        CHECK (source_url IS NULL OR source_url ~ '^https://[^[:space:]]+$'),
    CONSTRAINT catalog_datalab_region_mappings_verified_by_ck
        CHECK (verified_by IS NULL OR btrim(verified_by) <> ''),
    CONSTRAINT catalog_datalab_region_mappings_active_provenance_ck CHECK (
        status <> 'ACTIVE' OR (
            source_url IS NOT NULL
            AND source_observed_at IS NOT NULL
            AND verified_by IS NOT NULL
            AND verified_at IS NOT NULL
        )
    )
);

CREATE FUNCTION onmaru.validate_datalab_region_mapping_structure()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    catalog_level onmaru.catalog_region_level;
    catalog_parent_id uuid;
    parent_level onmaru.catalog_region_level;
    mapped_region_id uuid;
    mapped_valid_to date;
    verification_url text;
    verification_by varchar;
    verification_at timestamptz;
BEGIN
    SELECT region.level, region.parent_id, parent.level
      INTO catalog_level, catalog_parent_id, parent_level
      FROM onmaru.catalog_regions region
      LEFT JOIN onmaru.catalog_regions parent ON parent.id = region.parent_id
     WHERE region.id = NEW.region_id;

    SELECT source.region_id, source.valid_to
      INTO mapped_region_id, mapped_valid_to
      FROM onmaru.catalog_region_source_codes source
     WHERE source.provider = NEW.provider
       AND source.dataset = NEW.dataset
       AND source.source_code = NEW.source_code
       AND source.valid_from = NEW.valid_from;

    SELECT verification.official_source_url, verification.verified_by, verification.verified_at
      INTO verification_url, verification_by, verification_at
      FROM onmaru.catalog_region_source_code_verifications verification
     WHERE verification.provider = NEW.provider
       AND verification.dataset = NEW.dataset
       AND verification.source_code = NEW.source_code
       AND verification.valid_from = NEW.valid_from;

    IF catalog_level IS NULL
       OR mapped_region_id IS DISTINCT FROM NEW.region_id
       OR mapped_valid_to IS DISTINCT FROM NEW.valid_to
       OR NEW.level IS DISTINCT FROM catalog_level
       OR (NEW.level = 'SIDO' AND (catalog_parent_id IS NOT NULL OR NEW.source_code !~ '^SIDO:'))
       OR (NEW.level = 'SIGUNGU' AND (
            catalog_parent_id IS NULL OR parent_level IS DISTINCT FROM 'SIDO'
            OR NEW.source_code !~ '^SIGUNGU:'
       ))
       OR (NEW.status = 'ACTIVE' AND (
            verification_url IS DISTINCT FROM NEW.source_url
            OR verification_by IS DISTINCT FROM NEW.verified_by
            OR verification_at IS DISTINCT FROM NEW.verified_at
       )) THEN
        RAISE EXCEPTION 'invalid DataLab region mapping structure'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'catalog_datalab_region_mapping_structure';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER catalog_datalab_region_mapping_structure
BEFORE INSERT OR UPDATE ON onmaru.catalog_datalab_region_mappings
FOR EACH ROW
EXECUTE FUNCTION onmaru.validate_datalab_region_mapping_structure();

-- Replace V025's deferred registration function without mutating the deployed migration.
CREATE OR REPLACE FUNCTION onmaru.register_verified_datalab_visitor_region_source_codes()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    WITH verified_mappings (region_code, source_code, level, provider_name) AS (
        VALUES
            ('kr-11', 'SIDO:11', 'SIDO'::onmaru.catalog_region_level, '서울특별시'),
            ('kr-11-jongno', 'SIGUNGU:11110', 'SIGUNGU'::onmaru.catalog_region_level, '종로구'),
            ('kr-45', 'SIDO:52', 'SIDO'::onmaru.catalog_region_level, '전북특별자치도'),
            ('kr-45-jeonju', 'SIGUNGU:52110', 'SIGUNGU'::onmaru.catalog_region_level, '전주시')
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

    WITH verified_mappings (region_code, source_code, level, provider_name) AS (
        VALUES
            ('kr-11', 'SIDO:11', 'SIDO'::onmaru.catalog_region_level, '서울특별시'),
            ('kr-11-jongno', 'SIGUNGU:11110', 'SIGUNGU'::onmaru.catalog_region_level, '종로구'),
            ('kr-45', 'SIDO:52', 'SIDO'::onmaru.catalog_region_level, '전북특별자치도'),
            ('kr-45-jeonju', 'SIGUNGU:52110', 'SIGUNGU'::onmaru.catalog_region_level, '전주시')
    )
    INSERT INTO onmaru.catalog_datalab_region_mappings (
        provider, dataset, source_code, valid_from, valid_to, region_id, level, name,
        source_url, source_observed_at, verified_by, verified_at, status
    )
    SELECT
        source.provider, source.dataset, source.source_code, source.valid_from, source.valid_to,
        region.id, mapping.level, mapping.provider_name,
        verification.official_source_url,
        TIMESTAMPTZ '2026-09-26 09:00:00+09',
        verification.verified_by, verification.verified_at, 'ACTIVE'
    FROM verified_mappings mapping
    JOIN onmaru.catalog_regions region
      ON region.code = mapping.region_code
     AND region.active
    LEFT JOIN onmaru.catalog_regions parent ON parent.id = region.parent_id
    JOIN onmaru.catalog_region_source_codes source
      ON source.region_id = region.id
     AND source.provider = 'KTO_DATALAB'
     AND source.dataset = 'visitor'
     AND source.source_code = mapping.source_code
     AND source.valid_from = DATE '2026-09-26'
    JOIN onmaru.catalog_region_source_code_verifications verification
      ON verification.provider = source.provider
     AND verification.dataset = source.dataset
     AND verification.source_code = source.source_code
     AND verification.valid_from = source.valid_from
    WHERE region.level = mapping.level
      AND (
          (mapping.level = 'SIDO' AND region.parent_id IS NULL)
          OR (mapping.level = 'SIGUNGU' AND parent.level = 'SIDO')
      )
    ON CONFLICT (provider, dataset, source_code, valid_from) DO NOTHING;
END;
$$;

SELECT onmaru.register_verified_datalab_visitor_region_source_codes();

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '027', 'DATALAB_REGION_MAPPING_REGISTRY', 399,
    'Auditable DataLab region mapping registry with fail-closed status and provenance'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

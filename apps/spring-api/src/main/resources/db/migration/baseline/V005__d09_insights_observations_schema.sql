-- onmaru-checksum: d09-v005-20260915
-- Issue: #135 D09 Insights observation, target, and place link schema.

CREATE TABLE onmaru.insights_visitor_observations (
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id),
    region_id uuid NOT NULL REFERENCES onmaru.catalog_regions (id),
    basis_date date NOT NULL,
    visitor_type varchar NOT NULL,
    provider varchar NOT NULL,
    spatial_level varchar NOT NULL,
    visitor_count bigint NOT NULL,
    source_observed_at timestamptz,
    fetched_at timestamptz NOT NULL,
    PRIMARY KEY (revision_id, region_id, basis_date, visitor_type),
    CONSTRAINT insights_visitor_observations_count_ck CHECK (visitor_count >= 0),
    CONSTRAINT insights_visitor_observations_visitor_type_ck CHECK (btrim(visitor_type) <> ''),
    CONSTRAINT insights_visitor_observations_provider_ck CHECK (btrim(provider) <> ''),
    CONSTRAINT insights_visitor_observations_spatial_level_ck CHECK (
        spatial_level IN ('COUNTRY', 'PROVINCE', 'CITY', 'DISTRICT', 'SIDO', 'SIGUNGU')
    )
);

CREATE INDEX insights_visitor_observations_region_basis_date_idx
    ON onmaru.insights_visitor_observations (region_id, basis_date);

CREATE TABLE onmaru.insights_tourism_targets (
    id uuid PRIMARY KEY,
    provider varchar NOT NULL,
    source_target_key varchar NOT NULL,
    region_id uuid NOT NULL REFERENCES onmaru.catalog_regions (id),
    source_name varchar NOT NULL,
    CONSTRAINT insights_tourism_targets_provider_source_target_key_uq
        UNIQUE (provider, source_target_key),
    CONSTRAINT insights_tourism_targets_provider_ck CHECK (btrim(provider) <> ''),
    CONSTRAINT insights_tourism_targets_source_target_key_ck CHECK (btrim(source_target_key) <> ''),
    CONSTRAINT insights_tourism_targets_source_name_ck CHECK (btrim(source_name) <> '')
);

CREATE INDEX insights_tourism_targets_region_id_idx
    ON onmaru.insights_tourism_targets (region_id);

CREATE TABLE onmaru.insights_target_place_links (
    target_id uuid PRIMARY KEY REFERENCES onmaru.insights_tourism_targets (id),
    place_id uuid NOT NULL REFERENCES onmaru.catalog_place_identity (id),
    match_method varchar NOT NULL,
    verified_at timestamptz,
    CONSTRAINT insights_target_place_links_match_method_ck CHECK (btrim(match_method) <> '')
);

CREATE INDEX insights_target_place_links_place_id_idx
    ON onmaru.insights_target_place_links (place_id);

CREATE TABLE onmaru.insights_concentration_observations (
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id),
    target_id uuid NOT NULL REFERENCES onmaru.insights_tourism_targets (id),
    basis_date date NOT NULL,
    metric_type varchar NOT NULL,
    value numeric NOT NULL,
    unit varchar NOT NULL,
    source_observed_at timestamptz,
    fetched_at timestamptz NOT NULL,
    PRIMARY KEY (revision_id, target_id, basis_date, metric_type),
    CONSTRAINT insights_concentration_observations_metric_type_ck CHECK (
        metric_type IN ('VISITOR_COUNT', 'CONGESTION_SCORE', 'SURGE_MULTIPLIER')
    ),
    CONSTRAINT insights_concentration_observations_unit_ck CHECK (
        unit IN ('persons', 'score', 'multiplier')
    ),
    CONSTRAINT insights_concentration_observations_value_range_ck CHECK (
        (metric_type = 'VISITOR_COUNT' AND unit = 'persons' AND value >= 0)
        OR (metric_type = 'CONGESTION_SCORE' AND unit = 'score' AND value >= 0 AND value <= 100)
        OR (metric_type = 'SURGE_MULTIPLIER' AND unit = 'multiplier' AND value >= 0)
    )
);

CREATE INDEX insights_concentration_observations_target_basis_date_idx
    ON onmaru.insights_concentration_observations (target_id, basis_date);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '005',
    'D09',
    135,
    'Insights visitor observations, tourism targets, target-place links, and concentration observations schema'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

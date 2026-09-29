-- onmaru-checksum: map-info-read-projections-v034-20260929
-- Issue: #495 Publish the map information-mode read projections atomically with a catalog revision.

CREATE TABLE onmaru.map_place_read_projection (
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id) ON DELETE CASCADE,
    place_id uuid NOT NULL REFERENCES onmaru.catalog_place_identity (id),
    public_id varchar NOT NULL,
    name varchar NOT NULL,
    normalized_name varchar NOT NULL,
    status onmaru.catalog_place_status NOT NULL,
    location_geom geometry(Point, 4326) NOT NULL,
    sido_code varchar,
    sigungu_code varchar,
    eupmyeondong_code varchar,
    display_category varchar NOT NULL,
    thumbnail_url text,
    summary text,
    sort_key varchar NOT NULL,
    PRIMARY KEY (revision_id, place_id),
    CONSTRAINT map_place_read_projection_public_id_uq UNIQUE (revision_id, public_id),
    CONSTRAINT map_place_read_projection_name_ck CHECK (btrim(name) <> ''),
    CONSTRAINT map_place_read_projection_normalized_name_ck CHECK (btrim(normalized_name) <> ''),
    CONSTRAINT map_place_read_projection_category_ck CHECK (btrim(display_category) <> ''),
    CONSTRAINT map_place_read_projection_sort_key_ck CHECK (btrim(sort_key) <> ''),
    CONSTRAINT map_place_read_projection_location_srid_ck CHECK (ST_SRID(location_geom) = 4326)
);

CREATE INDEX map_place_read_projection_list_keyset_idx
    ON onmaru.map_place_read_projection (revision_id, sort_key, place_id);
CREATE INDEX map_place_read_projection_region_keyset_idx
    ON onmaru.map_place_read_projection (revision_id, sigungu_code, sort_key, place_id);
CREATE INDEX map_place_read_projection_status_category_idx
    ON onmaru.map_place_read_projection (revision_id, status, display_category, sort_key, place_id);
CREATE INDEX map_place_read_projection_location_gix
    ON onmaru.map_place_read_projection USING gist (location_geom);

CREATE TABLE onmaru.map_place_category_projection (
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id) ON DELETE CASCADE,
    place_id uuid NOT NULL REFERENCES onmaru.catalog_place_identity (id),
    canonical_category varchar NOT NULL,
    PRIMARY KEY (revision_id, place_id, canonical_category),
    CONSTRAINT map_place_category_projection_category_ck CHECK (btrim(canonical_category) <> '')
);

CREATE INDEX map_place_category_projection_category_idx
    ON onmaru.map_place_category_projection (revision_id, canonical_category, place_id);

CREATE TABLE onmaru.map_scope_count_projection (
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id) ON DELETE CASCADE,
    scope_type varchar NOT NULL,
    region_code varchar NOT NULL,
    canonical_category varchar NOT NULL,
    place_count integer NOT NULL,
    PRIMARY KEY (revision_id, scope_type, region_code, canonical_category),
    CONSTRAINT map_scope_count_projection_scope_ck CHECK (scope_type IN ('DISTRICT', 'REGION')),
    CONSTRAINT map_scope_count_projection_region_ck CHECK (btrim(region_code) <> ''),
    CONSTRAINT map_scope_count_projection_category_ck CHECK (btrim(canonical_category) <> ''),
    CONSTRAINT map_scope_count_projection_count_ck CHECK (place_count >= 0)
);

CREATE INDEX map_scope_count_projection_lookup_idx
    ON onmaru.map_scope_count_projection (revision_id, scope_type, region_code, canonical_category);

CREATE TABLE onmaru.map_projection_publications (
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id) ON DELETE CASCADE,
    projection_name varchar NOT NULL,
    mapping_version varchar NOT NULL,
    row_count integer NOT NULL,
    checksum varchar NOT NULL,
    published_at timestamptz NOT NULL,
    status onmaru.catalog_dataset_status NOT NULL,
    PRIMARY KEY (revision_id, projection_name),
    CONSTRAINT map_projection_publications_name_ck CHECK (btrim(projection_name) <> ''),
    CONSTRAINT map_projection_publications_mapping_ck CHECK (btrim(mapping_version) <> ''),
    CONSTRAINT map_projection_publications_count_ck CHECK (row_count >= 0),
    CONSTRAINT map_projection_publications_checksum_ck CHECK (checksum ~ '^[0-9a-f]{64}$')
);

GRANT SELECT, INSERT, UPDATE, DELETE ON
    onmaru.map_place_read_projection,
    onmaru.map_place_category_projection,
    onmaru.map_scope_count_projection,
    onmaru.map_projection_publications TO onmaru_runtime;
GRANT SELECT ON
    onmaru.map_place_read_projection,
    onmaru.map_place_category_projection,
    onmaru.map_scope_count_projection,
    onmaru.map_projection_publications TO onmaru_readonly;
GRANT ALL PRIVILEGES ON
    onmaru.map_place_read_projection,
    onmaru.map_place_category_projection,
    onmaru.map_scope_count_projection,
    onmaru.map_projection_publications TO onmaru_migration;

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '034', 'MAP_INFO_READ_PROJECTIONS', 495,
    'Information-mode map read projections, category mappings, scope counts, and publication metadata'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

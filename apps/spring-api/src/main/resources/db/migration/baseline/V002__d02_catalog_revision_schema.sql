-- onmaru-checksum: d02-v002-20260915
-- Issue: #75 D02 Catalog canonical place, source, and revision schema.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE onmaru.catalog_region_level AS ENUM ('SIDO', 'SIGUNGU');
CREATE TYPE onmaru.catalog_dataset_status AS ENUM ('STAGING', 'READY', 'PUBLISHED', 'FAILED');
CREATE TYPE onmaru.catalog_place_status AS ENUM ('ACTIVE', 'HIDDEN', 'DELETED');

CREATE TABLE onmaru.catalog_regions (
    id uuid PRIMARY KEY,
    parent_id uuid REFERENCES onmaru.catalog_regions (id),
    code varchar NOT NULL UNIQUE,
    name varchar NOT NULL,
    level onmaru.catalog_region_level NOT NULL,
    active boolean NOT NULL
);

CREATE TABLE onmaru.catalog_region_source_codes (
    provider varchar NOT NULL,
    dataset varchar NOT NULL,
    source_code varchar NOT NULL,
    valid_from date NOT NULL,
    region_id uuid NOT NULL REFERENCES onmaru.catalog_regions (id),
    valid_to date,
    PRIMARY KEY (provider, dataset, source_code, valid_from)
);

CREATE INDEX catalog_region_source_codes_region_id_idx
    ON onmaru.catalog_region_source_codes (region_id);

CREATE TABLE onmaru.catalog_region_boundaries (
    boundary_revision varchar NOT NULL,
    region_id uuid NOT NULL REFERENCES onmaru.catalog_regions (id),
    geometry geometry(MultiPolygon, 4326) NOT NULL,
    source_name varchar NOT NULL,
    rights_note text NOT NULL,
    observed_at timestamptz NOT NULL,
    PRIMARY KEY (boundary_revision, region_id)
);

CREATE INDEX catalog_region_boundaries_geometry_gix
    ON onmaru.catalog_region_boundaries USING gist (geometry);

CREATE TABLE onmaru.catalog_dataset_revisions (
    id uuid PRIMARY KEY,
    dataset varchar NOT NULL,
    status onmaru.catalog_dataset_status NOT NULL,
    base_revision_id uuid REFERENCES onmaru.catalog_dataset_revisions (id),
    source_observed_at timestamptz,
    fetched_at timestamptz NOT NULL,
    published_at timestamptz,
    CONSTRAINT catalog_dataset_revisions_published_at_ck CHECK (
        status <> 'PUBLISHED' OR published_at IS NOT NULL
    )
);

CREATE UNIQUE INDEX catalog_dataset_revisions_dataset_id_uq
    ON onmaru.catalog_dataset_revisions (dataset, id);
CREATE INDEX catalog_dataset_revisions_dataset_status_idx
    ON onmaru.catalog_dataset_revisions (dataset, status);

CREATE TABLE onmaru.catalog_active_datasets (
    dataset varchar PRIMARY KEY,
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id),
    activated_at timestamptz NOT NULL
);

CREATE TABLE onmaru.catalog_place_identity (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL
);

CREATE TABLE onmaru.catalog_place_sources (
    id uuid PRIMARY KEY,
    place_id uuid NOT NULL REFERENCES onmaru.catalog_place_identity (id),
    provider varchar NOT NULL,
    dataset varchar NOT NULL,
    external_id varchar NOT NULL,
    language varchar NOT NULL,
    fetched_at timestamptz NOT NULL,
    payload_hash varchar,
    CONSTRAINT catalog_place_sources_provider_dataset_external_id_language_uq
        UNIQUE (provider, dataset, external_id, language)
);

CREATE INDEX catalog_place_sources_place_id_idx
    ON onmaru.catalog_place_sources (place_id);

CREATE TABLE onmaru.catalog_place_versions (
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id),
    place_id uuid NOT NULL REFERENCES onmaru.catalog_place_identity (id),
    source_ref_id uuid NOT NULL REFERENCES onmaru.catalog_place_sources (id),
    region_id uuid REFERENCES onmaru.catalog_regions (id),
    name varchar NOT NULL,
    category varchar NOT NULL,
    address text,
    location geography(Point, 4326),
    overview text,
    visit_review_eligible boolean NOT NULL,
    status onmaru.catalog_place_status NOT NULL,
    normalized_hash varchar NOT NULL,
    PRIMARY KEY (revision_id, place_id)
);

CREATE INDEX catalog_place_versions_region_id_idx
    ON onmaru.catalog_place_versions (region_id);
CREATE INDEX catalog_place_versions_location_gix
    ON onmaru.catalog_place_versions USING gist (location);
CREATE INDEX catalog_place_versions_status_category_idx
    ON onmaru.catalog_place_versions (status, category);

CREATE TABLE onmaru.catalog_kto_korean_content_versions (
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id),
    source_ref_id uuid NOT NULL REFERENCES onmaru.catalog_place_sources (id),
    contentid varchar NOT NULL,
    contenttypeid varchar,
    title varchar NOT NULL,
    addr1 text,
    addr2 text,
    zipcode varchar,
    areacode varchar,
    sigungucode varchar,
    cat1 varchar,
    cat2 varchar,
    cat3 varchar,
    lcls_systm1 varchar,
    lcls_systm2 varchar,
    lcls_systm3 varchar,
    firstimage text,
    firstimage2 text,
    cpyrht_div_cd varchar,
    mapx numeric,
    mapy numeric,
    mlevel varchar,
    tel varchar,
    createdtime varchar,
    modifiedtime varchar,
    showflag varchar,
    raw_hash varchar NOT NULL,
    PRIMARY KEY (revision_id, source_ref_id)
);

CREATE INDEX catalog_kto_korean_content_versions_contentid_idx
    ON onmaru.catalog_kto_korean_content_versions (contentid);
CREATE INDEX catalog_kto_korean_content_versions_area_idx
    ON onmaru.catalog_kto_korean_content_versions (areacode, sigungucode);
CREATE INDEX catalog_kto_korean_content_versions_category_idx
    ON onmaru.catalog_kto_korean_content_versions (cat1, cat2, cat3);

CREATE TABLE onmaru.catalog_kto_korean_intro_versions (
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id),
    source_ref_id uuid NOT NULL REFERENCES onmaru.catalog_place_sources (id),
    heritage1 varchar,
    heritage2 varchar,
    heritage3 varchar,
    infocenter text,
    opendate text,
    restdate text,
    expguide text,
    expagerange text,
    accomcount text,
    useseason text,
    usetime text,
    parking text,
    chkbabycarriage text,
    chkpet text,
    chkcreditcard text,
    raw_hash varchar NOT NULL,
    PRIMARY KEY (revision_id, source_ref_id)
);

CREATE TABLE onmaru.catalog_kto_korean_info_versions (
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id),
    source_ref_id uuid NOT NULL REFERENCES onmaru.catalog_place_sources (id),
    serialnum varchar NOT NULL,
    infoname varchar,
    infotext text,
    fldgubun varchar,
    raw_hash varchar NOT NULL,
    PRIMARY KEY (revision_id, source_ref_id, serialnum)
);

CREATE TABLE onmaru.catalog_place_image_versions (
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id),
    place_id uuid NOT NULL REFERENCES onmaru.catalog_place_identity (id),
    position integer NOT NULL,
    origin_img_url text NOT NULL,
    small_image_url text,
    image_name varchar,
    source_ref_id uuid NOT NULL REFERENCES onmaru.catalog_place_sources (id),
    rights_note text,
    PRIMARY KEY (revision_id, place_id, position)
);

CREATE INDEX catalog_place_image_versions_source_ref_id_idx
    ON onmaru.catalog_place_image_versions (source_ref_id);

CREATE TABLE onmaru.catalog_hanok_detail_versions (
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id),
    place_id uuid NOT NULL REFERENCES onmaru.catalog_place_identity (id),
    type varchar,
    hours text,
    parking text,
    homepage text,
    source_ref_id uuid NOT NULL REFERENCES onmaru.catalog_place_sources (id),
    PRIMARY KEY (revision_id, place_id)
);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '002',
    'D02',
    75,
    'Catalog canonical place, source identity, dataset revision, and PostGIS projection schema'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

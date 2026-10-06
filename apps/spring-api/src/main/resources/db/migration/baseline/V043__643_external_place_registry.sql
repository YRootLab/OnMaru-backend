-- onmaru-checksum: external-place-registry-v043-20261006
-- Issue: #643. Client-asserted Kakao places backing VisitReview without publishing a Catalog revision.

CREATE TABLE onmaru.catalog_external_places (
    provider varchar NOT NULL,
    external_id varchar(128) NOT NULL,
    place_id uuid NOT NULL UNIQUE REFERENCES onmaru.catalog_place_identity (id),
    public_place_id varchar NOT NULL UNIQUE REFERENCES onmaru.catalog_place_public_ids (public_id),
    name varchar(100) NOT NULL,
    region_code varchar NOT NULL,
    location geography(Point, 4326) NOT NULL,
    provenance varchar NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (provider, external_id),
    CONSTRAINT catalog_external_places_provider_ck CHECK (provider = 'KAKAO'),
    CONSTRAINT catalog_external_places_external_id_ck CHECK (btrim(external_id) <> ''),
    CONSTRAINT catalog_external_places_name_ck CHECK (btrim(name) <> ''),
    CONSTRAINT catalog_external_places_region_code_ck CHECK (
        region_code = 'kr-unassigned'
        OR region_code ~ '^kr-[a-z0-9]+(?:-[a-z0-9]+)*$'
    ),
    CONSTRAINT catalog_external_places_provenance_ck CHECK (provenance = 'CLIENT_ASSERTED'),
    CONSTRAINT catalog_external_places_public_place_ck CHECK (
        public_place_id ~ '^p-ext-[a-f0-9]{32}$'
    )
);

CREATE INDEX catalog_external_places_location_gix
    ON onmaru.catalog_external_places USING gist (location);

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '043', 'EXTERNAL_PLACE_REGISTRY', 643,
    'Client-asserted Kakao place identity and immutable VisitReview snapshot source'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

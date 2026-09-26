-- onmaru-checksum: issue346-v020-20260924
-- Issue: #346 Stable FE-visible Catalog place IDs for VisitReview and map contracts.

CREATE TABLE onmaru.catalog_place_public_ids (
    public_id varchar PRIMARY KEY,
    place_id uuid NOT NULL UNIQUE REFERENCES onmaru.catalog_place_identity (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT catalog_place_public_ids_format_ck CHECK (
        public_id ~ '^p-[a-z0-9]+(-[a-z0-9]+)*$'
    )
);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '020',
    'FE-346',
    346,
    'Catalog-owned stable public place ID mapping'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

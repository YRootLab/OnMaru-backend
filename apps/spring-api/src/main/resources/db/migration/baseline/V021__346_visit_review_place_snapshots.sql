-- onmaru-checksum: issue346-v021-20260924
-- Issue: #346 Persist FE-visible VisitReview place fields at write time.

ALTER TABLE onmaru.community_visit_reviews
    ADD COLUMN public_place_id varchar REFERENCES onmaru.catalog_place_public_ids (public_id),
    ADD COLUMN place_name varchar,
    ADD COLUMN region_code varchar,
    ADD COLUMN latitude numeric(9, 6),
    ADD COLUMN longitude numeric(9, 6),
    ADD CONSTRAINT community_visit_reviews_public_place_id_format_ck CHECK (
        public_place_id IS NULL OR public_place_id ~ '^p-[a-z0-9]+(-[a-z0-9]+)*$'
    );

CREATE INDEX community_visit_reviews_public_place_created_at_idx
    ON onmaru.community_visit_reviews (public_place_id, created_at, id);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '021',
    'FE-346',
    346,
    'VisitReview immutable public place snapshot fields'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

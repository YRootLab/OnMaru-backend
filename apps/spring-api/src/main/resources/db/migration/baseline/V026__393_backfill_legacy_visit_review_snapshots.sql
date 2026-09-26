-- Issue #393: keep pre-V021 VisitReview rows visible after the JDBC store cut-over.
INSERT INTO onmaru.catalog_place_public_ids (public_id, place_id, created_at)
SELECT DISTINCT
    'p-legacy-' || replace(review.place_id::text, '-', ''),
    review.place_id,
    review.created_at
FROM onmaru.community_visit_reviews review
LEFT JOIN onmaru.catalog_place_public_ids mapping ON mapping.place_id = review.place_id
WHERE mapping.place_id IS NULL
ON CONFLICT DO NOTHING;

UPDATE onmaru.community_visit_reviews review
SET public_place_id = mapping.public_id,
    place_name = COALESCE(review.place_name, current_place.name, mapping.public_id),
    region_code = COALESCE(review.region_code, current_place.region_code, 'kr-unknown'),
    latitude = COALESCE(review.latitude, current_place.latitude),
    longitude = COALESCE(review.longitude, current_place.longitude)
FROM onmaru.catalog_place_public_ids mapping
LEFT JOIN LATERAL (
    SELECT version.name,
           region.code AS region_code,
           ST_Y(version.location::geometry) AS latitude,
           ST_X(version.location::geometry) AS longitude
    FROM onmaru.catalog_place_versions version
    LEFT JOIN onmaru.catalog_regions region ON region.id = version.region_id
    JOIN onmaru.catalog_dataset_revisions revision ON revision.id = version.revision_id
    WHERE version.place_id = mapping.place_id
      AND version.status = 'ACTIVE'
      AND revision.status = 'PUBLISHED'
    ORDER BY revision.published_at DESC NULLS LAST, revision.fetched_at DESC
    LIMIT 1
) current_place ON true
WHERE review.place_id = mapping.place_id
  AND review.public_place_id IS NULL;

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '026', 'FE-393', 393,
    'Backfill stable public IDs and immutable snapshots for legacy VisitReview rows'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

-- onmaru-checksum: map-info-legacy-backfill-v037-20261001
-- Issue #553: V036 introduced the map read model after catalog data was already published.
-- Backfill the active revision without changing the source catalog or active pointer.
DO $backfill$
DECLARE
    active_revision uuid;
    source_count integer;
    projection_count integer;
    projection_checksum varchar;
BEGIN
    SELECT active.revision_id INTO active_revision
    FROM onmaru.catalog_active_datasets active
    JOIN onmaru.catalog_dataset_revisions revision ON revision.id = active.revision_id
    WHERE active.dataset = 'kto-korean-tour' AND revision.status = 'PUBLISHED'
    FOR UPDATE OF active;

    IF active_revision IS NULL OR EXISTS (
        SELECT 1 FROM onmaru.map_projection_publications
        WHERE revision_id = active_revision AND projection_name = 'map_place_read_projection'
    ) THEN
        RETURN;
    END IF;

    IF EXISTS (SELECT 1 FROM onmaru.map_place_read_projection WHERE revision_id = active_revision) THEN
        RAISE EXCEPTION 'unpublished map projection rows exist for revision %', active_revision;
    END IF;

    SELECT count(*) INTO source_count
    FROM onmaru.catalog_place_versions version
    LEFT JOIN onmaru.catalog_place_public_ids public_id ON public_id.place_id = version.place_id
    WHERE version.revision_id = active_revision AND version.status = 'ACTIVE'
      AND (version.location IS NULL OR public_id.public_id IS NULL);
    IF source_count <> 0 THEN
        RAISE EXCEPTION 'active map source has % places without coordinates or public ID', source_count;
    END IF;

    INSERT INTO onmaru.map_place_read_projection (
        revision_id, place_id, public_id, name, normalized_name, status,
        location_geom, sido_code, sigungu_code, eupmyeondong_code,
        display_category, thumbnail_url, summary, sort_key
    )
    SELECT version.revision_id, version.place_id, public_id.public_id,
           version.name, lower(btrim(version.name)), version.status,
           version.location::geometry,
           COALESCE(parent.code, raw.ldong_regn_cd,
                    CASE WHEN region.level = 'SIDO' THEN region.code END),
           CASE WHEN region.level = 'SIGUNGU' THEN region.code
                ELSE NULLIF(concat_ws(':', raw.ldong_regn_cd, raw.ldong_signgu_cd), '') END,
           NULL, version.category, image.origin_img_url, version.overview,
           lower(btrim(version.name)) || '|' || public_id.public_id
    FROM onmaru.catalog_place_versions version
    JOIN onmaru.catalog_place_public_ids public_id ON public_id.place_id = version.place_id
    LEFT JOIN onmaru.catalog_regions region ON region.id = version.region_id AND region.active
    LEFT JOIN onmaru.catalog_regions parent ON parent.id = region.parent_id AND parent.active
    LEFT JOIN onmaru.catalog_kto_korean_content_versions raw
      ON raw.revision_id = version.revision_id AND raw.source_ref_id = version.source_ref_id
    LEFT JOIN onmaru.catalog_place_image_versions image
      ON image.revision_id = version.revision_id AND image.place_id = version.place_id AND image.position = 0
    WHERE version.revision_id = active_revision AND version.status = 'ACTIVE';

    SELECT count(*) INTO source_count FROM onmaru.catalog_place_versions
    WHERE revision_id = active_revision AND status = 'ACTIVE';
    SELECT count(*) INTO projection_count FROM onmaru.map_place_read_projection
    WHERE revision_id = active_revision;
    IF source_count <> projection_count THEN
        RAISE EXCEPTION 'map backfill count mismatch: source %, projection %', source_count, projection_count;
    END IF;

    INSERT INTO onmaru.map_place_category_projection (revision_id, place_id, canonical_category)
    SELECT revision_id, place_id,
           CASE upper(display_category)
               WHEN 'HANOK' THEN 'SPOT'
               WHEN 'HISTORIC_SITE' THEN 'SPOT'
               WHEN 'CULTURE_ART' THEN 'CULTURE'
               WHEN 'HANOK_VILLAGE' THEN 'SPOT'
               WHEN 'GOTAEK' THEN 'SPOT'
               WHEN 'HANOK_EXPERIENCE' THEN 'EXPERIENCE'
               WHEN 'LOCAL_SCENE' THEN 'EXPERIENCE'
               WHEN 'HANOK_STAY' THEN 'STAY'
               WHEN 'HANOK_HOTEL' THEN 'STAY'
               WHEN 'TRADITIONAL_FOOD' THEN 'FOOD'
               WHEN 'KOREAN_RESTAURANT' THEN 'FOOD'
               WHEN 'RESTAURANT' THEN 'FOOD'
               WHEN 'HANOK_CAFE' THEN 'CAFE'
               WHEN 'TEA_HOUSE' THEN 'CAFE'
               WHEN 'COFFEE_SHOP' THEN 'CAFE'
               WHEN 'TRADITIONAL_MARKET' THEN 'MARKET'
               WHEN 'LOCAL_MARKET' THEN 'MARKET'
               WHEN 'EVENT' THEN 'FESTIVAL'
               ELSE upper(display_category)
           END
    FROM onmaru.map_place_read_projection
    WHERE revision_id = active_revision;

    INSERT INTO onmaru.map_scope_count_projection
        (revision_id, scope_type, region_code, canonical_category, place_count)
    WITH scoped_places AS (
        SELECT projection.revision_id, 'DISTRICT'::varchar AS scope_type,
               projection.sigungu_code AS region_code, projection.place_id,
               category.canonical_category
        FROM onmaru.map_place_read_projection projection
        JOIN onmaru.map_place_category_projection category
          ON category.revision_id = projection.revision_id AND category.place_id = projection.place_id
        WHERE projection.revision_id = active_revision AND projection.sigungu_code IS NOT NULL
        UNION ALL
        SELECT projection.revision_id, 'REGION'::varchar AS scope_type,
               projection.sido_code AS region_code, projection.place_id,
               category.canonical_category
        FROM onmaru.map_place_read_projection projection
        JOIN onmaru.map_place_category_projection category
          ON category.revision_id = projection.revision_id AND category.place_id = projection.place_id
        WHERE projection.revision_id = active_revision AND projection.sido_code IS NOT NULL
    )
    SELECT revision_id, scope_type, region_code, canonical_category,
           count(DISTINCT place_id)::integer
    FROM scoped_places
    GROUP BY revision_id, scope_type, region_code, canonical_category;

    SELECT encode(digest(coalesce(string_agg(
               place_id::text || '|' || public_id || '|' || name || '|' ||
               display_category || '|' || ST_AsEWKT(location_geom),
               E'\n' ORDER BY place_id), ''), 'sha256'), 'hex')
    INTO projection_checksum
    FROM onmaru.map_place_read_projection WHERE revision_id = active_revision;

    INSERT INTO onmaru.map_projection_publications
        (revision_id, projection_name, mapping_version, row_count, checksum, published_at, status)
    VALUES (active_revision, 'map_place_read_projection', 'map-category-v1',
            projection_count, projection_checksum, clock_timestamp(), 'PUBLISHED');
END
$backfill$;

INSERT INTO onmaru_registry.migration_version_reservations
    (version, reserved_for, issue_number, description)
VALUES ('037', 'MAP_INFO_LEGACY_BACKFILL', 553,
        'Backfill active map read projections published before V036')
ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

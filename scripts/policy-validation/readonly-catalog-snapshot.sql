-- Run with the production read-only role and PGOPTIONS=-c default_transaction_read_only=on.
-- Output one JSON object per line with psql -XAt; keep the raw file outside Git.
SELECT json_build_object(
    'contentid', s.contentid,
    'lcls', s.lcls_systm3,
    'title', s.title,
    'addr', s.addr1,
    'region', s.areacode,
    'sigungucode', s.sigungucode,
    'mapx', s.mapx,
    'mapy', s.mapy,
    'modifiedtime', s.modifiedtime,
    'rawHash', s.raw_hash,
    'placeId', p.place_id,
    'status', p.status,
    'category', p.category,
    'name', p.name,
    'overview', p.overview,
    'publicId', id.public_id
)::text
FROM onmaru.catalog_kto_korean_content_versions s
JOIN onmaru.catalog_active_datasets a
  ON a.revision_id = s.revision_id AND a.dataset = 'kto-korean-tour'
LEFT JOIN onmaru.catalog_place_versions p
  ON p.revision_id = s.revision_id AND p.source_ref_id = s.source_ref_id
LEFT JOIN onmaru.catalog_place_public_ids id ON id.place_id = p.place_id;

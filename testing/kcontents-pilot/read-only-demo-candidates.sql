-- #691: inspect real production catalog identities for the seven FE fallback places.
-- Run only with the production read-only login. Keep output outside Git.
BEGIN READ ONLY;
SET LOCAL statement_timeout = '15s';

-- The production schema may still be on V043. Record that before planning a write.
SELECT json_build_object(
    'kContentsTable', to_regclass('onmaru.k_contents')::text,
    'relationsTable', to_regclass('onmaru.k_content_place_relations')::text,
    'activeCatalogRevision', (
      SELECT revision_id FROM onmaru.catalog_active_datasets
      WHERE dataset = 'kto-korean-tour'
    )
)::text;

WITH targets(display_name, name_pattern) AS (
  VALUES
    ('안동 만휴정', '%만휴정%'),
    ('서천 이하복 고택', '%이하복%고택%'),
    ('남원 광한루원', '%광한루원%'),
    ('안동 하회마을 양진당', '%양진당%'),
    ('담양 명옥헌원림', '%명옥헌%'),
    ('완주 아원고택', '%아원고택%'),
    ('강릉 선교장', '%선교장%')
)
SELECT json_build_object(
    'target', t.display_name,
    'contentId', s.contentid,
    'sourceTitle', s.title,
    'address', s.addr1,
    'areaCode', s.areacode,
    'sigunguCode', s.sigungucode,
    'sourceClass', s.lcls_systm3,
    'placeId', p.place_id,
    'publicId', pid.public_id,
    'placeStatus', p.status,
    'placeName', p.name,
    'sourceRevision', s.revision_id
)::text
FROM targets t
JOIN onmaru.catalog_kto_korean_content_versions s
  ON s.title ILIKE t.name_pattern
JOIN onmaru.catalog_active_datasets a
  ON a.dataset = 'kto-korean-tour' AND a.revision_id = s.revision_id
LEFT JOIN onmaru.catalog_place_versions p
  ON p.revision_id = s.revision_id AND p.source_ref_id = s.source_ref_id
LEFT JOIN onmaru.catalog_place_public_ids pid ON pid.place_id = p.place_id
ORDER BY t.display_name, s.title, s.contentid;

COMMIT;

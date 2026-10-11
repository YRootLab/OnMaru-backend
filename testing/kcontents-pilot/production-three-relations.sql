-- #691: reviewed, bounded first production labels from existing active TourAPI places.
-- Execute only after V046-V049 are deployed. This rule labels only the three
-- official-source facts whose active place IDs and addresses were checked.
-- Dry run: psql -X -v ON_ERROR_STOP=1 -f production-three-relations.sql
-- Apply:   psql -X -v ON_ERROR_STOP=1 -v apply=1 -f production-three-relations.sql
-- The entire script is one transaction; no human-review status is asserted.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL statement_timeout = '15s';
SET LOCAL lock_timeout = '3s';

CREATE TEMP TABLE pilot_labels (
  content_id varchar(40) PRIMARY KEY,
  expected_place_id uuid NOT NULL,
  public_id text NOT NULL,
  address_prefix text NOT NULL,
  work_title text NOT NULL,
  normalized_title text NOT NULL,
  source_url text NOT NULL,
  source_title text NOT NULL,
  filming_excerpt text NOT NULL
) ON COMMIT DROP;

INSERT INTO pilot_labels VALUES
  ('126998', '686c9614-5d97-3b13-b946-7691492c3021', 'p-tourapi-126998',
   '경상북도 안동시 길안면 묵계하리길 42', '미스터 션샤인', '미스터션샤인',
   'https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=d4594ee6-ca20-4083-87fa-f22670b1c5f2',
   'MZ 외국인 친구들과 안동 여행, 안동구시장 찜닭 바로 여기!',
   'tvN 드라마 미스터 션샤인 촬영지로 유명하다.'),
  ('125800', 'f112eb98-e94f-3c3c-ba97-ae18469e9dbe', 'p-tourapi-125800',
   '강원특별자치도 강릉시 운정길 63', '사임당 빛의 일기', '사임당빛의일기',
   'https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=50441e4a-309d-4fcc-bcbd-81cb2affcef6',
   '강원 - 강릉선교장, 한옥에서 보내는 특별한 하루',
   '사임당 빛의 일기 촬영지이기도 하다.'),
  ('126397', 'b398e867-8ab8-38cd-8982-982b2839b4df', 'p-tourapi-126397',
   '전북특별자치도 남원시 요천로 1447', '옷소매 붉은 끝동', '옷소매붉은끝동',
   'https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=2408dce3-d53c-43d8-8cd3-915488da62d1',
   '오래된 나무와 고풍스런 누각, 아름다운 연못에 뜬 달 - 남원 광한루원',
   '드라마 옷소매 붉은끝동 촬영지로 관심을 받았다.');

DO $$
BEGIN
  IF (
    SELECT count(*) FROM pilot_labels l
    JOIN onmaru.catalog_kto_korean_content_versions s ON s.contentid = l.content_id
      AND s.addr1 LIKE l.address_prefix || '%'
    JOIN onmaru.catalog_active_datasets a ON a.dataset = 'kto-korean-tour'
      AND a.revision_id = s.revision_id
    JOIN onmaru.catalog_place_versions p ON p.revision_id = s.revision_id
      AND p.source_ref_id = s.source_ref_id AND p.status = 'ACTIVE'
      AND p.place_id = l.expected_place_id
    JOIN onmaru.catalog_place_public_ids pid ON pid.place_id = p.place_id
      AND pid.public_id = l.public_id
  ) <> 3 THEN
    RAISE EXCEPTION 'Active source/place identity changed; recheck production catalog';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pilot_labels l JOIN onmaru.k_contents w
      ON w.normalized_title = l.normalized_title AND w.work_type = 'DRAMA'
    WHERE w.id <> md5('onmaru:pilot691:work:' || l.normalized_title)::uuid
  ) THEN
    RAISE EXCEPTION 'Existing canonical work identity needs manual reconciliation';
  END IF;
END $$;

INSERT INTO onmaru.k_contents
  (id, title, normalized_title, work_type, status, verified_by, verified_at, rule_version)
SELECT md5('onmaru:pilot691:work:' || normalized_title)::uuid,
       work_title, normalized_title, 'DRAMA', 'AUTO_VERIFIED',
       'official-source-rule-691', now(), 'official-tourism-location-v1'
FROM pilot_labels
ON CONFLICT (id) DO NOTHING;

INSERT INTO onmaru.k_content_metadata_sources
  (id, k_content_id, canonical_url, source_type, title, publisher, observed_at,
   status, verified_by, verified_at, rule_version)
SELECT md5('onmaru:pilot691:metadata:' || content_id)::uuid,
       md5('onmaru:pilot691:work:' || normalized_title)::uuid,
       source_url, 'OFFICIAL', source_title, '한국관광공사', now(),
       'VERIFIED', 'official-source-rule-691', now(),
       'official-tourism-location-v1'
FROM pilot_labels
ON CONFLICT (k_content_id, canonical_url) DO NOTHING;

INSERT INTO onmaru.k_content_place_relations
  (id, place_id, k_content_id, relation_type, status, verified_by, verified_at, rule_version)
SELECT md5('onmaru:pilot691:relation:' || content_id)::uuid,
       expected_place_id, md5('onmaru:pilot691:work:' || normalized_title)::uuid,
       'FILMING_LOCATION', 'AUTO_VERIFIED',
       'official-source-rule-691', now(), 'official-tourism-location-v1'
FROM pilot_labels
ON CONFLICT (place_id, k_content_id, relation_type) DO NOTHING;

INSERT INTO onmaru.k_content_relation_evidence
  (id, relation_id, canonical_url, source_type, title, publisher, filming_excerpt,
   observed_at, status, verified_by, verified_at, rule_version)
SELECT md5('onmaru:pilot691:evidence:' || content_id)::uuid,
       md5('onmaru:pilot691:relation:' || content_id)::uuid,
       source_url, 'OFFICIAL', source_title, '한국관광공사', filming_excerpt,
       now(), 'VERIFIED', 'official-source-rule-691', now(),
       'official-tourism-location-v1'
FROM pilot_labels
ON CONFLICT (relation_id, canonical_url) DO NOTHING;

SELECT l.content_id, l.public_id, w.id AS work_id, r.id AS relation_id,
       w.status AS work_status, r.status AS relation_status, e.status AS evidence_status
FROM pilot_labels l
JOIN onmaru.k_contents w
  ON w.id = md5('onmaru:pilot691:work:' || l.normalized_title)::uuid
JOIN onmaru.k_content_place_relations r
  ON r.id = md5('onmaru:pilot691:relation:' || l.content_id)::uuid
JOIN onmaru.k_content_relation_evidence e
  ON e.id = md5('onmaru:pilot691:evidence:' || l.content_id)::uuid
ORDER BY l.content_id;

\if :{?apply}
COMMIT;
\else
ROLLBACK;
\endif

-- 10만 건 VisitReview 집계 및 인덱스 실행 계획 (EXPLAIN ANALYZE) 검증 쿼리

-- 1. 지역별 활성 후기 집계 (Insights 모듈)
-- 목표: 100,000건 테이블에서 region_code 기반 필터 및 통계 집계 시 Index Scan 적용 (목표 실행 시간 < 100ms)
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT
    vr.region_code,
    COUNT(*) AS total_reviews,
    AVG(vr.rating) AS avg_rating,
    COUNT(DISTINCT vr.place_id) AS reviewed_places_count
FROM community.visit_reviews vr
WHERE vr.region_code = 'kr-45-jeonju'
  AND vr.status = 'PUBLISHED'
  AND vr.deleted_at IS NULL
GROUP BY vr.region_code;

-- 2. 장소별 후기 목록 페이징 (Cursor pagination)
-- 목표: place_id 및 created_at 복합 인덱스(idx_visit_reviews_place_created)를 활용하여 Offset 없이 Index Scan (목표 실행 시간 < 50ms)
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT
    vr.id,
    vr.place_id,
    vr.member_id,
    vr.rating,
    vr.content,
    vr.created_at
FROM community.visit_reviews vr
WHERE vr.place_id = 'p-jeonju-hanok-village'
  AND vr.status = 'PUBLISHED'
  AND vr.deleted_at IS NULL
  AND vr.created_at < '2026-09-17T12:00:00Z'
ORDER BY vr.created_at DESC, vr.id DESC
LIMIT 20;

-- 3. 한옥 장소 카탈로그 복합 검색
-- 목표: region_code, content_type 및 정렬 인덱스 활용 (목표 실행 시간 < 50ms)
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT
    p.id,
    p.name,
    p.category,
    p.region_code,
    p.created_at
FROM catalog.places p
WHERE p.region_code = 'kr-45-jeonju'
  AND p.status = 'ACTIVE'
  AND p.deleted_at IS NULL
ORDER BY p.created_at DESC
LIMIT 20;

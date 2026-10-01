\set ON_ERROR_STOP on
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'onmaru_staging' THEN
        RAISE EXCEPTION 'Refusing staging seed in database %', current_database();
    END IF;
END;
$$;

-- Deliberately synthetic records. No production member, session, or source data is copied.
INSERT INTO onmaru.catalog_regions (id, code, name, level, active) VALUES
  ('54500000-0000-4000-8000-000000000001', 'STG', '스테이징 시도', 'SIDO', true),
  ('54500000-0000-4000-8000-000000000002', 'STG-01', '스테이징 시군구', 'SIGUNGU', true)
ON CONFLICT (id) DO NOTHING;
UPDATE onmaru.catalog_regions
SET parent_id = '54500000-0000-4000-8000-000000000001'
WHERE id = '54500000-0000-4000-8000-000000000002';

INSERT INTO onmaru.catalog_dataset_revisions
  (id, dataset, status, fetched_at, published_at)
VALUES
  ('54500000-0000-4000-8000-000000000010', 'kto-korean-tour', 'PUBLISHED', now(), now())
ON CONFLICT (id) DO NOTHING;
INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
VALUES ('kto-korean-tour', '54500000-0000-4000-8000-000000000010', now())
ON CONFLICT (dataset) DO NOTHING;

INSERT INTO onmaru.catalog_place_identity (id, created_at) VALUES
  ('54500000-0000-4000-8000-000000000021', now()),
  ('54500000-0000-4000-8000-000000000022', now())
ON CONFLICT (id) DO NOTHING;
INSERT INTO onmaru.catalog_place_sources
  (id, place_id, provider, dataset, external_id, language, fetched_at)
VALUES
  ('54500000-0000-4000-8000-000000000031', '54500000-0000-4000-8000-000000000021', 'STAGING', 'synthetic', 'hanok-a', 'ko', now()),
  ('54500000-0000-4000-8000-000000000032', '54500000-0000-4000-8000-000000000022', 'STAGING', 'synthetic', 'hanok-b', 'ko', now())
ON CONFLICT (id) DO NOTHING;
INSERT INTO onmaru.catalog_place_public_ids (public_id, place_id) VALUES
  ('p-staging-hanok-a', '54500000-0000-4000-8000-000000000021'),
  ('p-staging-hanok-b', '54500000-0000-4000-8000-000000000022')
ON CONFLICT (public_id) DO NOTHING;
INSERT INTO onmaru.catalog_place_versions
  (revision_id, place_id, source_ref_id, region_id, name, category, address,
   location, overview, visit_review_eligible, status, normalized_hash)
VALUES
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000021',
   '54500000-0000-4000-8000-000000000031', '54500000-0000-4000-8000-000000000002',
   '스테이징 테스트 한옥 A', 'HANOK', '테스트 전용 가상 주소',
   ST_SetSRID(ST_MakePoint(127.000, 37.500), 4326)::geography,
   '실제 관광지가 아닌 합성 테스트 데이터입니다.', true, 'ACTIVE', 'staging-hanok-a'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000022',
   '54500000-0000-4000-8000-000000000032', '54500000-0000-4000-8000-000000000002',
   '스테이징 테스트 한옥 B', 'HANOK_CAFE', '테스트 전용 가상 주소',
   ST_SetSRID(ST_MakePoint(127.010, 37.505), 4326)::geography,
   '실제 관광지가 아닌 합성 테스트 데이터입니다.', true, 'ACTIVE', 'staging-hanok-b')
ON CONFLICT (revision_id, place_id) DO NOTHING;

INSERT INTO onmaru.map_place_read_projection
  (revision_id, place_id, public_id, name, normalized_name, status, location_geom,
   sido_code, sigungu_code, display_category, summary, sort_key)
VALUES
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000021',
   'p-staging-hanok-a', '스테이징 테스트 한옥 A', '스테이징 테스트 한옥 a', 'ACTIVE',
   ST_SetSRID(ST_MakePoint(127.000, 37.500), 4326), 'STG', 'STG-01', 'HANOK',
   '실제 관광지가 아닌 합성 테스트 데이터입니다.', 'staging-hanok-a'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000022',
   'p-staging-hanok-b', '스테이징 테스트 한옥 B', '스테이징 테스트 한옥 b', 'ACTIVE',
   ST_SetSRID(ST_MakePoint(127.010, 37.505), 4326), 'STG', 'STG-01', 'HANOK_CAFE',
   '실제 관광지가 아닌 합성 테스트 데이터입니다.', 'staging-hanok-b')
ON CONFLICT (revision_id, place_id) DO NOTHING;
INSERT INTO onmaru.map_place_category_projection (revision_id, place_id, canonical_category)
VALUES
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000021', 'HANOK'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000022', 'HANOK_CAFE')
ON CONFLICT (revision_id, place_id, canonical_category) DO NOTHING;
INSERT INTO onmaru.map_projection_publications
  (revision_id, projection_name, mapping_version, row_count, checksum, published_at, status)
VALUES
  ('54500000-0000-4000-8000-000000000010', 'map_place_read_projection', 'map-zoom-v1', 2,
   encode(digest('onmaru-staging-map-fixture-v1', 'sha256'), 'hex'), now(), 'PUBLISHED')
ON CONFLICT (revision_id, projection_name) DO NOTHING;
COMMIT;

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
  ('54500000-0000-4000-8000-000000000010', 'kto-korean-tour', 'PUBLISHED', '2026-10-06T00:00:00Z', '2026-10-06T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000011', 'odii-audio', 'PUBLISHED', '2026-10-06T00:00:00Z', '2026-10-06T00:00:00Z')
ON CONFLICT (id) DO NOTHING;
INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
VALUES
  ('kto-korean-tour', '54500000-0000-4000-8000-000000000010', '2026-10-06T00:00:00Z'),
  ('odii-audio', '54500000-0000-4000-8000-000000000011', '2026-10-06T00:00:00Z')
ON CONFLICT (dataset) DO UPDATE
SET revision_id = EXCLUDED.revision_id, activated_at = EXCLUDED.activated_at;

INSERT INTO onmaru.catalog_place_identity (id, created_at) VALUES
  ('54500000-0000-4000-8000-000000000021', '2026-10-06T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000022', '2026-10-06T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000023', '2026-10-06T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000024', '2026-10-06T00:00:00Z')
ON CONFLICT (id) DO NOTHING;
INSERT INTO onmaru.catalog_place_sources
  (id, place_id, provider, dataset, external_id, language, fetched_at)
VALUES
  ('54500000-0000-4000-8000-000000000031', '54500000-0000-4000-8000-000000000021', 'STAGING', 'synthetic', 'hanok-a', 'ko', '2026-10-06T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000032', '54500000-0000-4000-8000-000000000022', 'STAGING', 'synthetic', 'hanok-b', 'ko', '2026-10-06T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000033', '54500000-0000-4000-8000-000000000023', 'STAGING', 'synthetic', 'palace-c', 'ko', '2026-10-06T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000034', '54500000-0000-4000-8000-000000000024', 'STAGING', 'synthetic', 'market-d', 'ko', '2026-10-06T00:00:00Z')
ON CONFLICT (id) DO NOTHING;
INSERT INTO onmaru.catalog_place_public_ids (public_id, place_id) VALUES
  ('p-staging-hanok-a', '54500000-0000-4000-8000-000000000021'),
  ('p-staging-hanok-b', '54500000-0000-4000-8000-000000000022'),
  ('p-staging-palace-c', '54500000-0000-4000-8000-000000000023'),
  ('p-staging-market-d', '54500000-0000-4000-8000-000000000024')
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
   '선택 필드가 적은 합성 테스트 데이터입니다.', true, 'ACTIVE', 'staging-hanok-b'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000023',
   '54500000-0000-4000-8000-000000000033', '54500000-0000-4000-8000-000000000002',
   '스테이징 이야기 궁궐 C', 'HISTORIC_SITE', '테스트 전용 가상 주소 3',
   ST_SetSRID(ST_MakePoint(127.020, 37.510), 4326)::geography,
   '장소 상세와 오디오 연결을 확인하는 합성 데이터입니다.', true, 'ACTIVE', 'staging-palace-c'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000024',
   '54500000-0000-4000-8000-000000000034', '54500000-0000-4000-8000-000000000002',
   '스테이징 전통시장 D', 'TRADITIONAL_MARKET', '테스트 전용 가상 주소 4',
   ST_SetSRID(ST_MakePoint(127.030, 37.515), 4326)::geography,
   '카테고리 필터를 확인하는 합성 데이터입니다.', true, 'ACTIVE', 'staging-market-d')
ON CONFLICT (revision_id, place_id) DO UPDATE SET
  source_ref_id = EXCLUDED.source_ref_id,
  region_id = EXCLUDED.region_id,
  name = EXCLUDED.name,
  category = EXCLUDED.category,
  address = EXCLUDED.address,
  location = EXCLUDED.location,
  overview = EXCLUDED.overview,
  visit_review_eligible = EXCLUDED.visit_review_eligible,
  status = EXCLUDED.status,
  normalized_hash = EXCLUDED.normalized_hash;

INSERT INTO onmaru.catalog_place_content_tag_versions
  (revision_id, place_id, position, label, score, source, algorithm_version, source_hash, generated_at)
VALUES
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000021', 0, '고즈넉함', 1, 'GENERATED', 'staging-v1', 'tag-a-0', '2026-10-06T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000021', 1, '오디오가이드', 1, 'GENERATED', 'staging-v1', 'tag-a-1', '2026-10-06T00:00:00Z')
ON CONFLICT (revision_id, place_id, position) DO NOTHING;

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
   '선택 필드가 적은 합성 테스트 데이터입니다.', 'staging-hanok-b'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000023',
   'p-staging-palace-c', '스테이징 이야기 궁궐 C', '스테이징 이야기 궁궐 c', 'ACTIVE',
   ST_SetSRID(ST_MakePoint(127.020, 37.510), 4326), 'STG', 'STG-01', 'HISTORIC_SITE',
   '장소 상세와 오디오 연결을 확인하는 합성 데이터입니다.', 'staging-palace-c'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000024',
   'p-staging-market-d', '스테이징 전통시장 D', '스테이징 전통시장 d', 'ACTIVE',
   ST_SetSRID(ST_MakePoint(127.030, 37.515), 4326), 'STG', 'STG-01', 'TRADITIONAL_MARKET',
   '카테고리 필터를 확인하는 합성 데이터입니다.', 'staging-market-d')
ON CONFLICT (revision_id, place_id) DO UPDATE SET
  public_id = EXCLUDED.public_id,
  name = EXCLUDED.name,
  normalized_name = EXCLUDED.normalized_name,
  status = EXCLUDED.status,
  location_geom = EXCLUDED.location_geom,
  sido_code = EXCLUDED.sido_code,
  sigungu_code = EXCLUDED.sigungu_code,
  eupmyeondong_code = EXCLUDED.eupmyeondong_code,
  display_category = EXCLUDED.display_category,
  thumbnail_url = EXCLUDED.thumbnail_url,
  summary = EXCLUDED.summary,
  sort_key = EXCLUDED.sort_key;

INSERT INTO onmaru.catalog_kto_korean_content_versions
  (revision_id, source_ref_id, contentid, contenttypeid, title, addr1, areacode, sigungucode,
   cat1, cat2, cat3, firstimage, mapx, mapy, raw_hash)
VALUES
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000031', 'staging-1001', '12', '스테이징 테스트 한옥 A', '테스트 전용 가상 주소', 'STG', '01', 'A02', 'A0201', 'A02011600', 'https://picsum.photos/seed/onmaru-hanok-a/1200/800', 127.000, 37.500, 'staging-content-a'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000032', 'staging-1002', '39', '스테이징 테스트 한옥 B', '테스트 전용 가상 주소', 'STG', '01', 'A05', 'A0502', 'A05020900', NULL, 127.010, 37.505, 'staging-content-b'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000033', 'staging-1003', '12', '스테이징 이야기 궁궐 C', '테스트 전용 가상 주소 3', 'STG', '01', 'A02', 'A0201', 'A02010100', 'https://picsum.photos/seed/onmaru-palace-c/1200/800', 127.020, 37.510, 'staging-content-c'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000034', 'staging-1004', '38', '스테이징 전통시장 D', '테스트 전용 가상 주소 4', 'STG', '01', 'A04', 'A0401', 'A04010200', 'https://picsum.photos/seed/onmaru-market-d/1200/800', 127.030, 37.515, 'staging-content-d')
ON CONFLICT (revision_id, source_ref_id) DO NOTHING;

INSERT INTO onmaru.catalog_place_image_versions
  (revision_id, place_id, position, origin_img_url, small_image_url, image_name, source_ref_id, rights_note)
VALUES
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000021', 0, 'https://picsum.photos/seed/onmaru-hanok-a/1200/800', 'https://picsum.photos/seed/onmaru-hanok-a/400/300', '합성 한옥 이미지', '54500000-0000-4000-8000-000000000031', '스테이징 합성 fixture'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000023', 0, 'https://picsum.photos/seed/onmaru-palace-c/1200/800', 'https://picsum.photos/seed/onmaru-palace-c/400/300', '합성 궁궐 이미지', '54500000-0000-4000-8000-000000000033', '스테이징 합성 fixture'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000024', 0, 'https://picsum.photos/seed/onmaru-market-d/1200/800', 'https://picsum.photos/seed/onmaru-market-d/400/300', '합성 시장 이미지', '54500000-0000-4000-8000-000000000034', '스테이징 합성 fixture')
ON CONFLICT (revision_id, place_id, position) DO NOTHING;

INSERT INTO onmaru.catalog_hanok_detail_versions
  (revision_id, place_id, type, hours, parking, homepage, source_ref_id)
VALUES
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000021', '전통문화시설', '09:00~18:00', '스테이징 전용 주차 정보', 'https://staging.onmaru.site', '54500000-0000-4000-8000-000000000031')
ON CONFLICT (revision_id, place_id) DO NOTHING;
INSERT INTO onmaru.map_place_category_projection (revision_id, place_id, canonical_category)
VALUES
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000021', 'SPOT'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000022', 'CAFE'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000023', 'SPOT'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000024', 'MARKET')
ON CONFLICT (revision_id, place_id, canonical_category) DO NOTHING;

INSERT INTO onmaru.identity_members (id, status, created_at) VALUES
  ('54500000-0000-4000-8000-000000000101', 'ACTIVE', '2026-10-01T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000102', 'ACTIVE', '2026-10-01T00:00:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO onmaru.community_visit_reviews
  (id, member_id, place_id, text, status, created_at, mood, score, tags,
   public_place_id, place_name, region_code, latitude, longitude)
VALUES
  ('54500000-0000-4000-8000-000000000111', '54500000-0000-4000-8000-000000000101', '54500000-0000-4000-8000-000000000021', '처마 아래에서 잠시 쉬기 좋았어요.', 'PUBLISHED', '2026-10-05T03:00:00Z', '한적', 5, '["고즈넉함", "산책"]', 'p-staging-hanok-a', '스테이징 테스트 한옥 A', 'STG-01', 37.500, 127.000),
  ('54500000-0000-4000-8000-000000000112', '54500000-0000-4000-8000-000000000102', '54500000-0000-4000-8000-000000000021', '오디오 설명과 함께 둘러보기 좋은 합성 후기입니다.', 'PUBLISHED', '2026-10-04T03:00:00Z', '북적', 4, '["오디오", "가족"]', 'p-staging-hanok-a', '스테이징 테스트 한옥 A', 'STG-01', 37.500, 127.000),
  ('54500000-0000-4000-8000-000000000113', '54500000-0000-4000-8000-000000000101', '54500000-0000-4000-8000-000000000023', '이 후기는 숨김 상태 검증용이라 공개 응답에 나오면 안 됩니다.', 'HIDDEN', '2026-10-03T03:00:00Z', '한적', 3, '["숨김"]', 'p-staging-palace-c', '스테이징 이야기 궁궐 C', 'STG-01', 37.510, 127.020)
ON CONFLICT (id) DO NOTHING;

INSERT INTO onmaru.community_review_likes (review_id, member_id, created_at) VALUES
  ('54500000-0000-4000-8000-000000000111', '54500000-0000-4000-8000-000000000102', '2026-10-05T04:00:00Z')
ON CONFLICT (review_id, member_id) DO NOTHING;

INSERT INTO onmaru.audio_odii_spots (id, provider, tid, tlid, lang_code, created_at) VALUES
  ('54500000-0000-4000-8000-000000000201', 'STAGING', 'spot-a', 'spot-a-ko', 'ko', '2026-10-01T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000202', 'STAGING', 'spot-c', 'spot-c-ko', 'ko', '2026-10-01T00:00:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO onmaru.audio_odii_stories (id, spot_id, provider, stid, stlid, lang_code, created_at) VALUES
  ('54500000-0000-4000-8000-000000000211', '54500000-0000-4000-8000-000000000201', 'STAGING', 'story-a-1', 'story-a-1-ko', 'ko', '2026-10-01T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000212', '54500000-0000-4000-8000-000000000201', 'STAGING', 'story-a-2', 'story-a-2-ko', 'ko', '2026-10-01T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000213', '54500000-0000-4000-8000-000000000202', 'STAGING', 'story-c-1', 'story-c-1-ko', 'ko', '2026-10-01T00:00:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO onmaru.audio_spot_versions
  (revision_id, spot_id, title, address, location, status, hash, source_modified_at)
VALUES
  ('54500000-0000-4000-8000-000000000011', '54500000-0000-4000-8000-000000000201', '스테이징 한옥 오디오 스팟', '테스트 전용 가상 주소', ST_SetSRID(ST_MakePoint(127.000, 37.500), 4326)::geography, 'ACTIVE', 'staging-spot-a', '2026-10-05T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000011', '54500000-0000-4000-8000-000000000202', '스테이징 궁궐 오디오 스팟', '테스트 전용 가상 주소 3', ST_SetSRID(ST_MakePoint(127.020, 37.510), 4326)::geography, 'ACTIVE', 'staging-spot-c', '2026-10-04T00:00:00Z')
ON CONFLICT (revision_id, spot_id) DO NOTHING;

INSERT INTO onmaru.audio_story_versions
  (revision_id, story_id, spot_id, title, script, audio_url, image_url, duration_seconds,
   status, hash, transcript_provenance, source_modified_at)
VALUES
  ('54500000-0000-4000-8000-000000000011', '54500000-0000-4000-8000-000000000211', '54500000-0000-4000-8000-000000000201', '한옥의 처마 이야기', '스테이징 화면과 자막 렌더링을 확인하기 위한 합성 대본입니다.', 'https://samplelib.com/lib/preview/mp3/sample-3s.mp3', 'https://picsum.photos/seed/onmaru-audio-a1/1200/800', 3, 'ACTIVE', 'staging-story-a-1', 'OFFICIAL', '2026-10-05T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000011', '54500000-0000-4000-8000-000000000212', '54500000-0000-4000-8000-000000000201', '한옥 마당의 하루', '목록과 페이지네이션을 확인하기 위한 두 번째 합성 대본입니다.', 'https://samplelib.com/lib/preview/mp3/sample-6s.mp3', NULL, 6, 'ACTIVE', 'staging-story-a-2', 'OFFICIAL', '2026-10-04T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000011', '54500000-0000-4000-8000-000000000213', '54500000-0000-4000-8000-000000000202', '궁궐 문을 지나는 법', '검색과 근처 조회를 확인하기 위한 합성 대본입니다.', 'https://samplelib.com/lib/preview/mp3/sample-3s.mp3', 'https://picsum.photos/seed/onmaru-audio-c1/1200/800', 3, 'ACTIVE', 'staging-story-c-1', 'OFFICIAL', '2026-10-03T00:00:00Z')
ON CONFLICT (revision_id, story_id) DO NOTHING;

INSERT INTO onmaru.audio_subtitle_lines
  (revision_id, story_id, position, text, start_seconds, timing_mode)
VALUES
  ('54500000-0000-4000-8000-000000000011', '54500000-0000-4000-8000-000000000211', 0, '한옥의 처마 이야기를 시작합니다.', 0, 'ESTIMATED'),
  ('54500000-0000-4000-8000-000000000011', '54500000-0000-4000-8000-000000000211', 1, '이 내용은 스테이징 전용 합성 데이터입니다.', 1.5, 'ESTIMATED')
ON CONFLICT (revision_id, story_id, position) DO NOTHING;

INSERT INTO onmaru.audio_story_content_tag_versions
  (revision_id, story_id, position, label, score, source, algorithm_version, source_hash, generated_at)
VALUES
  ('54500000-0000-4000-8000-000000000011', '54500000-0000-4000-8000-000000000211', 0, '한옥', 1, 'GENERATED', 'staging-v1', 'audio-tag-a-0', '2026-10-06T00:00:00Z'),
  ('54500000-0000-4000-8000-000000000011', '54500000-0000-4000-8000-000000000213', 0, '궁궐', 1, 'GENERATED', 'staging-v1', 'audio-tag-c-0', '2026-10-06T00:00:00Z')
ON CONFLICT (revision_id, story_id, position) DO NOTHING;

INSERT INTO onmaru.audio_place_odii_links
  (place_id, spot_id, match_method, confidence, verified_at, review_status)
VALUES
  ('54500000-0000-4000-8000-000000000021', '54500000-0000-4000-8000-000000000201', 'STAGING_FIXTURE', 1, '2026-10-06T00:00:00Z', 'APPROVED'),
  ('54500000-0000-4000-8000-000000000023', '54500000-0000-4000-8000-000000000202', 'STAGING_FIXTURE', 1, '2026-10-06T00:00:00Z', 'APPROVED')
ON CONFLICT (place_id, spot_id) DO NOTHING;

-- Issue #675 exclusively owns UUIDs with the 54500675-* prefix.
-- Regenerate only that range, children first, while preserving the connected 54500000-* fixtures.
DELETE FROM onmaru.audio_place_odii_links
WHERE place_id::text LIKE '54500675-%' OR spot_id::text LIKE '54500675-%';
DELETE FROM onmaru.audio_story_content_tag_versions WHERE story_id::text LIKE '54500675-%';
DELETE FROM onmaru.audio_subtitle_lines WHERE story_id::text LIKE '54500675-%';
DELETE FROM onmaru.audio_story_versions WHERE story_id::text LIKE '54500675-%';
DELETE FROM onmaru.audio_odii_stories WHERE id::text LIKE '54500675-%';
DELETE FROM onmaru.audio_spot_versions WHERE spot_id::text LIKE '54500675-%';
DELETE FROM onmaru.audio_odii_spots WHERE id::text LIKE '54500675-%';
DELETE FROM onmaru.community_review_likes WHERE review_id::text LIKE '54500675-%';
DELETE FROM onmaru.community_visit_reviews WHERE id::text LIKE '54500675-%';
DELETE FROM onmaru.map_place_category_projection WHERE place_id::text LIKE '54500675-%';
DELETE FROM onmaru.map_place_read_projection WHERE place_id::text LIKE '54500675-%';
DELETE FROM onmaru.catalog_place_image_versions WHERE place_id::text LIKE '54500675-%';
DELETE FROM onmaru.catalog_place_content_tag_versions WHERE place_id::text LIKE '54500675-%';
DELETE FROM onmaru.catalog_hanok_detail_versions WHERE place_id::text LIKE '54500675-%';
DELETE FROM onmaru.catalog_place_versions WHERE place_id::text LIKE '54500675-%';
DELETE FROM onmaru.catalog_place_public_ids WHERE place_id::text LIKE '54500675-%';
DELETE FROM onmaru.catalog_kto_korean_info_versions WHERE source_ref_id::text LIKE '54500675-%';
DELETE FROM onmaru.catalog_kto_korean_intro_versions WHERE source_ref_id::text LIKE '54500675-%';
DELETE FROM onmaru.catalog_kto_korean_content_versions WHERE source_ref_id::text LIKE '54500675-%';
DELETE FROM onmaru.catalog_place_sources WHERE id::text LIKE '54500675-%';
DELETE FROM onmaru.catalog_place_identity WHERE id::text LIKE '54500675-%';
DELETE FROM onmaru.catalog_regions WHERE id::text LIKE '54500675-%' AND parent_id IS NOT NULL;
DELETE FROM onmaru.catalog_regions WHERE id::text LIKE '54500675-%';

-- Transaction-local inputs keep all generated tables aligned on the same deterministic IDs and coordinates.
CREATE TEMP TABLE staging_fixture_regions ON COMMIT DROP AS
WITH regions(ord, sido_id, district_id, sido_code, district_code, sido_name, district_name,
             base_lng, base_lat) AS (VALUES
  (0, '54500675-0000-4000-8100-000000000001'::uuid,
      '54500675-0000-4000-8200-000000000001'::uuid,
      'STG-SEOUL', 'STG-SEOUL-01', '합성 서울권', '합성 서울지구', 126.9780, 37.5665),
  (1, '54500675-0000-4000-8100-000000000002'::uuid,
      '54500675-0000-4000-8200-000000000002'::uuid,
      'STG-JEONJU', 'STG-JEONJU-01', '합성 전주권', '합성 전주지구', 127.1480, 35.8242),
  (2, '54500675-0000-4000-8100-000000000003'::uuid,
      '54500675-0000-4000-8200-000000000003'::uuid,
      'STG-GYEONGJU', 'STG-GYEONGJU-01', '합성 경주권', '합성 경주지구', 129.2247, 35.8562),
  (3, '54500675-0000-4000-8100-000000000004'::uuid,
      '54500675-0000-4000-8200-000000000004'::uuid,
      'STG-BUSAN', 'STG-BUSAN-01', '합성 부산권', '합성 부산지구', 129.0756, 35.1796)
)
SELECT * FROM regions;

INSERT INTO onmaru.catalog_regions (id, parent_id, code, name, level, active)
SELECT sido_id, NULL::uuid, sido_code, sido_name, 'SIDO'::onmaru.catalog_region_level, true
FROM staging_fixture_regions;
INSERT INTO onmaru.catalog_regions (id, parent_id, code, name, level, active)
SELECT district_id, sido_id, district_code, district_name, 'SIGUNGU'::onmaru.catalog_region_level, true
FROM staging_fixture_regions;

CREATE TEMP TABLE staging_fixture_places ON COMMIT DROP AS
SELECT n,
       ('54500675-0000-4000-8300-' || lpad(n::text, 12, '0'))::uuid AS place_id,
       ('54500675-0000-4000-8400-' || lpad(n::text, 12, '0'))::uuid AS source_id,
       'p-staging-generated-' || lpad(n::text, 3, '0') AS public_id,
       sido_code, district_code, district_id,
       district_name || ' 장소 ' || lpad(n::text, 3, '0') AS name,
       (ARRAY['HANOK', 'HANOK_CAFE', 'TRADITIONAL_MARKET'])[(n - 1) % 3 + 1] AS category,
       (ARRAY['SPOT', 'CAFE', 'MARKET'])[(n - 1) % 3 + 1] AS canonical_category,
       '합성 주소 ' || district_name || ' ' || n AS address,
       '실제 관광지가 아닌 페이지네이션 검증용 합성 장소입니다.'::text AS overview,
       ST_SetSRID(ST_MakePoint(
         base_lng + (((n - 1) / 4) % 6) * 0.0025,
         base_lat + (((n - 1) / 24) % 4) * 0.0025
       ), 4326) AS location_geom,
       CASE WHEN n % 3 <> 0
         THEN 'https://picsum.photos/seed/onmaru-generated-' || lpad(n::text, 3, '0') || '/400/300'
       END AS thumbnail_url
FROM generate_series(1, 96) AS series(n)
JOIN staging_fixture_regions region ON region.ord = (n - 1) % 4;

INSERT INTO onmaru.catalog_place_identity (id, created_at)
SELECT place_id, '2026-10-06T00:00:00Z' FROM staging_fixture_places;
INSERT INTO onmaru.catalog_place_sources
  (id, place_id, provider, dataset, external_id, language, fetched_at)
SELECT source_id, place_id, 'STAGING', 'synthetic', 'generated-' || lpad(n::text, 3, '0'),
       'ko', '2026-10-06T00:00:00Z'
FROM staging_fixture_places;
INSERT INTO onmaru.catalog_place_public_ids (public_id, place_id)
SELECT public_id, place_id FROM staging_fixture_places;
INSERT INTO onmaru.catalog_place_versions
  (revision_id, place_id, source_ref_id, region_id, name, category, address,
   location, overview, visit_review_eligible, status, normalized_hash)
SELECT '54500000-0000-4000-8000-000000000010', place_id, source_id, district_id,
       name, category, address, location_geom::geography, overview, true, 'ACTIVE',
       'staging-generated-' || lpad(n::text, 3, '0')
FROM staging_fixture_places;
INSERT INTO onmaru.catalog_place_content_tag_versions
  (revision_id, place_id, position, label, score, source, algorithm_version, source_hash, generated_at)
SELECT '54500000-0000-4000-8000-000000000010', place_id, 0, '합성장소', 1,
       'GENERATED', 'staging-v1', 'generated-tag-' || n, '2026-10-06T00:00:00Z'
FROM staging_fixture_places;
INSERT INTO onmaru.catalog_place_image_versions
  (revision_id, place_id, position, origin_img_url, small_image_url, image_name, source_ref_id, rights_note)
SELECT '54500000-0000-4000-8000-000000000010', place_id, 0,
       'https://picsum.photos/seed/onmaru-generated-' || lpad(n::text, 3, '0') || '/1200/800',
       thumbnail_url, '합성 장소 이미지 ' || n, source_id, '스테이징 합성 fixture'
FROM staging_fixture_places WHERE n % 3 <> 0;
INSERT INTO onmaru.map_place_read_projection
  (revision_id, place_id, public_id, name, normalized_name, status, location_geom,
   sido_code, sigungu_code, display_category, thumbnail_url, summary, sort_key)
SELECT '54500000-0000-4000-8000-000000000010', place_id, public_id, name, lower(name),
       'ACTIVE', location_geom, sido_code, district_code, category, thumbnail_url, overview,
       'staging-generated-' || lpad(n::text, 3, '0')
FROM staging_fixture_places;
INSERT INTO onmaru.map_place_category_projection (revision_id, place_id, canonical_category)
SELECT '54500000-0000-4000-8000-000000000010', place_id, canonical_category
FROM staging_fixture_places;

-- Rebuild derived counts for the canonical map revision, including the original STG cluster.
DELETE FROM onmaru.map_scope_count_projection
WHERE revision_id = '54500000-0000-4000-8000-000000000010';
INSERT INTO onmaru.map_scope_count_projection
  (revision_id, scope_type, region_code, canonical_category, place_count)
SELECT place.revision_id, 'REGION', place.sido_code, category.canonical_category, count(*)
FROM onmaru.map_place_read_projection place
JOIN onmaru.map_place_category_projection category
  ON category.revision_id = place.revision_id AND category.place_id = place.place_id
WHERE place.revision_id = '54500000-0000-4000-8000-000000000010' AND place.status = 'ACTIVE'
GROUP BY place.revision_id, place.sido_code, category.canonical_category
UNION ALL
SELECT place.revision_id, 'DISTRICT', place.sigungu_code, category.canonical_category, count(*)
FROM onmaru.map_place_read_projection place
JOIN onmaru.map_place_category_projection category
  ON category.revision_id = place.revision_id AND category.place_id = place.place_id
WHERE place.revision_id = '54500000-0000-4000-8000-000000000010' AND place.status = 'ACTIVE'
GROUP BY place.revision_id, place.sigungu_code, category.canonical_category;
INSERT INTO onmaru.map_projection_publications
  (revision_id, projection_name, mapping_version, row_count, checksum, published_at, status)
SELECT '54500000-0000-4000-8000-000000000010', 'map_place_read_projection', 'map-zoom-v1', count(*),
       encode(digest(string_agg(public_id, ',' ORDER BY public_id), 'sha256'), 'hex'),
       '2026-10-06T00:00:00Z', 'PUBLISHED'
FROM onmaru.map_place_read_projection
WHERE revision_id = '54500000-0000-4000-8000-000000000010'
ON CONFLICT (revision_id, projection_name) DO UPDATE
SET mapping_version = EXCLUDED.mapping_version,
    row_count = EXCLUDED.row_count,
    checksum = EXCLUDED.checksum,
    published_at = EXCLUDED.published_at,
    status = EXCLUDED.status;

CREATE TEMP TABLE staging_fixture_reviews ON COMMIT DROP AS
SELECT n,
       ('54500675-0000-4000-8500-' || lpad(n::text, 12, '0'))::uuid AS member_id,
       ('54500675-0000-4000-8600-' || lpad(n::text, 12, '0'))::uuid AS review_id,
       CASE WHEN n <= 29 THEN '54500000-0000-4000-8000-000000000021'::uuid
         ELSE ('54500675-0000-4000-8300-' || lpad((n - 29)::text, 12, '0'))::uuid
       END AS place_id,
       '2026-10-06T12:00:00Z'::timestamptz - (n - 1) * interval '1 minute' AS created_at
FROM generate_series(1, 63) AS series(n);
INSERT INTO onmaru.identity_members (id, status, created_at)
SELECT member_id, 'ACTIVE', '2026-10-01T00:00:00Z' FROM staging_fixture_reviews
ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, created_at = EXCLUDED.created_at;
INSERT INTO onmaru.community_visit_reviews
  (id, member_id, place_id, text, status, created_at, mood, score, tags,
   public_place_id, place_name, region_code, latitude, longitude)
SELECT review.review_id, review.member_id, review.place_id,
       '페이지네이션을 확인하는 합성 방문 후기 ' || lpad(review.n::text, 3, '0') || '입니다.',
       'PUBLISHED', review.created_at, '한적', 5, '["합성후기", "산책"]'::jsonb,
       place.public_id, place.name, place.sigungu_code,
       ST_Y(place.location_geom), ST_X(place.location_geom)
FROM staging_fixture_reviews review
JOIN onmaru.map_place_read_projection place
  ON place.place_id = review.place_id AND place.revision_id = '54500000-0000-4000-8000-000000000010';

CREATE TEMP TABLE staging_fixture_stories ON COMMIT DROP AS
SELECT place.*,
       ('54500675-0000-4000-8700-' || lpad(n::text, 12, '0'))::uuid AS spot_id,
       ('54500675-0000-4000-8800-' || lpad(n::text, 12, '0'))::uuid AS story_id,
       '2026-10-06T12:00:00Z'::timestamptz - (n - 1) * interval '1 minute' AS source_modified_at,
       CASE WHEN n % 2 = 0 THEN 6 ELSE 3 END AS duration_seconds
FROM staging_fixture_places place WHERE n <= 62;
INSERT INTO onmaru.audio_odii_spots (id, provider, tid, tlid, lang_code, created_at)
SELECT spot_id, 'STAGING', 'generated-spot-' || n, 'generated-spot-' || n || '-ko',
       'ko', '2026-10-01T00:00:00Z'
FROM staging_fixture_stories;
INSERT INTO onmaru.audio_odii_stories (id, spot_id, provider, stid, stlid, lang_code, created_at)
SELECT story_id, spot_id, 'STAGING', 'generated-story-' || n, 'generated-story-' || n || '-ko',
       'ko', '2026-10-01T00:00:00Z'
FROM staging_fixture_stories;
INSERT INTO onmaru.audio_spot_versions
  (revision_id, spot_id, title, address, location, status, hash, source_modified_at)
SELECT '54500000-0000-4000-8000-000000000011', spot_id, name || ' 오디오 스팟', address,
       location_geom::geography, 'ACTIVE', 'staging-generated-spot-' || n, source_modified_at
FROM staging_fixture_stories;
INSERT INTO onmaru.audio_story_versions
  (revision_id, story_id, spot_id, title, script, audio_url, image_url, duration_seconds,
   status, hash, transcript_provenance, source_modified_at)
SELECT '54500000-0000-4000-8000-000000000011', story_id, spot_id,
       '합성 오디오 이야기 ' || lpad(n::text, 3, '0'),
       '오디오 목록과 자막 렌더링을 확인하는 스테이징 합성 대본 ' || n || '입니다.',
       'https://samplelib.com/lib/preview/mp3/sample-' || duration_seconds || 's.mp3',
       thumbnail_url, duration_seconds, 'ACTIVE', 'staging-generated-story-' || n,
       'OFFICIAL', source_modified_at
FROM staging_fixture_stories;
INSERT INTO onmaru.audio_subtitle_lines
  (revision_id, story_id, position, text, start_seconds, timing_mode)
SELECT '54500000-0000-4000-8000-000000000011', story_id, 0,
       '스테이징 합성 오디오 이야기 ' || n || '를 시작합니다.', 0, 'ESTIMATED'
FROM staging_fixture_stories;
INSERT INTO onmaru.audio_story_content_tag_versions
  (revision_id, story_id, position, label, score, source, algorithm_version, source_hash, generated_at)
SELECT '54500000-0000-4000-8000-000000000011', story_id, 0, '합성이야기', 1,
       'GENERATED', 'staging-v1', 'generated-story-tag-' || n, '2026-10-06T00:00:00Z'
FROM staging_fixture_stories;
INSERT INTO onmaru.audio_place_odii_links
  (place_id, spot_id, match_method, confidence, verified_at, review_status)
SELECT place_id, spot_id, 'STAGING_FIXTURE', 1, '2026-10-06T00:00:00Z', 'APPROVED'
FROM staging_fixture_stories;
COMMIT;

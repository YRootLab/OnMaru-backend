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
DELETE FROM onmaru.map_scope_count_projection
WHERE revision_id = '54500000-0000-4000-8000-000000000010';
DELETE FROM onmaru.map_place_category_projection
WHERE revision_id = '54500000-0000-4000-8000-000000000010';

INSERT INTO onmaru.map_place_category_projection (revision_id, place_id, canonical_category)
VALUES
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000021', 'SPOT'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000022', 'CAFE'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000023', 'SPOT'),
  ('54500000-0000-4000-8000-000000000010', '54500000-0000-4000-8000-000000000024', 'MARKET')
ON CONFLICT (revision_id, place_id, canonical_category) DO NOTHING;
INSERT INTO onmaru.map_scope_count_projection
  (revision_id, scope_type, region_code, canonical_category, place_count)
VALUES
  ('54500000-0000-4000-8000-000000000010', 'REGION', 'STG', 'SPOT', 2),
  ('54500000-0000-4000-8000-000000000010', 'REGION', 'STG', 'CAFE', 1),
  ('54500000-0000-4000-8000-000000000010', 'REGION', 'STG', 'MARKET', 1),
  ('54500000-0000-4000-8000-000000000010', 'DISTRICT', 'STG-01', 'SPOT', 2),
  ('54500000-0000-4000-8000-000000000010', 'DISTRICT', 'STG-01', 'CAFE', 1),
  ('54500000-0000-4000-8000-000000000010', 'DISTRICT', 'STG-01', 'MARKET', 1)
ON CONFLICT (revision_id, scope_type, region_code, canonical_category) DO UPDATE
SET place_count = EXCLUDED.place_count;
INSERT INTO onmaru.map_projection_publications
  (revision_id, projection_name, mapping_version, row_count, checksum, published_at, status)
VALUES
  ('54500000-0000-4000-8000-000000000010', 'map_place_read_projection', 'map-zoom-v1', 4,
   encode(digest('onmaru-staging-map-fixture-v1', 'sha256'), 'hex'), '2026-10-06T00:00:00Z', 'PUBLISHED')
ON CONFLICT (revision_id, projection_name) DO UPDATE
SET mapping_version = EXCLUDED.mapping_version,
    row_count = EXCLUDED.row_count,
    checksum = EXCLUDED.checksum,
    published_at = EXCLUDED.published_at,
    status = EXCLUDED.status;

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
COMMIT;

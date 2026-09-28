-- Issue #454: Java UUID.nameUUIDFromBytes와 동일한 공개 ID를 PostgreSQL 조회 키로 승격한다.
-- identity 테이블은 운영 기준 6,205행이므로 index 생성 시간을 먼저 측정한 뒤
-- Flyway transaction 안에서 원자적으로 생성한다. CONCURRENTLY는 Flyway의 schema-history
-- 연결이 virtual transaction을 유지하는 통합 환경에서 자기 대기 상태를 만들어 사용하지 않는다.

CREATE OR REPLACE FUNCTION onmaru.java_name_uuid(value text)
RETURNS uuid
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
    WITH raw AS (
        SELECT digest(convert_to(value, 'UTF8'), 'md5') AS bytes
    ), versioned AS (
        SELECT set_byte(
                set_byte(bytes, 6, (get_byte(bytes, 6) & 15) | 48),
                8,
                (get_byte(bytes, 8) & 63) | 128
        ) AS bytes
        FROM raw
    )
    SELECT encode(bytes, 'hex')::uuid
    FROM versioned
$$;

ALTER TABLE onmaru.audio_odii_spots
    ADD COLUMN IF NOT EXISTS public_id uuid
    GENERATED ALWAYS AS (
        onmaru.java_name_uuid(provider || ':odii-spot-:' || tid)
    ) STORED;

ALTER TABLE onmaru.audio_odii_stories
    ADD COLUMN IF NOT EXISTS public_id uuid
    GENERATED ALWAYS AS (
        onmaru.java_name_uuid(provider || ':odii-story-:' || stid)
    ) STORED;

-- 같은 provider tid/stid의 번역 identity는 public_id를 공유하므로 언어별로 유일하다.
-- public_id 단독 unique는 정상적인 ko/en 행을 서로 충돌시키므로 사용하지 않는다.
CREATE UNIQUE INDEX IF NOT EXISTS audio_odii_spots_public_language_idx
    ON onmaru.audio_odii_spots (public_id, lang_code);

CREATE UNIQUE INDEX IF NOT EXISTS audio_odii_stories_public_language_idx
    ON onmaru.audio_odii_stories (public_id, lang_code);

-- 목록은 활성 revision에서 최신순으로 소수 행만 읽는다. script 본문을 포함하는 heap을
-- 모두 훑지 않도록 정렬 키까지만 둔 작은 partial index를 사용한다.
CREATE INDEX IF NOT EXISTS audio_story_versions_active_page_idx
    ON onmaru.audio_story_versions (
        revision_id,
        source_modified_at DESC,
        story_id
    )
    WHERE status = 'ACTIVE' AND source_modified_at IS NOT NULL;

-- 인기 조회는 최근 기간을 먼저 자른 뒤 story_id로 집계한다. 기존 (story_id, occurred_at)은
-- 개별 story 집계에는 적합하지만 시간 범위 전체 집계에는 선두 컬럼이 맞지 않는다.
CREATE INDEX IF NOT EXISTS audio_story_play_events_occurred_story_idx
    ON onmaru.audio_story_play_events (occurred_at, story_id);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '033',
    '454',
    454,
    'Indexable public IDs for direct Odii PostgreSQL reads'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

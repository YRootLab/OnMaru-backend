-- V017 (#318): 찜(장소·오디오 스토리) 영속화와 오디오 재생 이벤트 테이블.
-- journey_saved_resources.resource_id는 uuid 타입이라 문자열 공개 ID(place_id, story_id)를
-- 수용하지 못하므로, 런타임이 사용하는 공개 ID 기반의 분리 테이블을 둔다.
-- (docs/database/schema.dbml "conditional FK" 비고 참조)

CREATE TABLE onmaru.journey_saved_places (
    id uuid PRIMARY KEY,
    member_id uuid NOT NULL REFERENCES onmaru.identity_members (id),
    place_id varchar NOT NULL,
    saved_at timestamptz NOT NULL,
    CONSTRAINT journey_saved_places_place_id_ck CHECK (btrim(place_id) <> ''),
    CONSTRAINT journey_saved_places_member_place_uq UNIQUE (member_id, place_id)
);

CREATE INDEX journey_saved_places_saved_at_idx
    ON onmaru.journey_saved_places (saved_at, id);

CREATE INDEX journey_saved_places_place_saved_at_idx
    ON onmaru.journey_saved_places (place_id, saved_at);

CREATE TABLE onmaru.journey_saved_odii_stories (
    id uuid PRIMARY KEY,
    member_id uuid NOT NULL REFERENCES onmaru.identity_members (id),
    story_id varchar NOT NULL,
    saved_at timestamptz NOT NULL,
    CONSTRAINT journey_saved_odii_stories_story_id_ck CHECK (btrim(story_id) <> ''),
    CONSTRAINT journey_saved_odii_stories_member_story_uq UNIQUE (member_id, story_id)
);

CREATE INDEX journey_saved_odii_stories_saved_at_idx
    ON onmaru.journey_saved_odii_stories (saved_at, id);

CREATE INDEX journey_saved_odii_stories_story_saved_at_idx
    ON onmaru.journey_saved_odii_stories (story_id, saved_at);

CREATE TABLE onmaru.audio_story_play_events (
    id uuid PRIMARY KEY,
    story_id varchar NOT NULL,
    occurred_at timestamptz NOT NULL
);

CREATE INDEX audio_story_play_events_story_occurred_idx
    ON onmaru.audio_story_play_events (story_id, occurred_at);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '017',
    '318',
    318,
    'Persist saved places, saved odii stories, and audio play events'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

-- V019 (#343): 스크린 한옥 자동 게시 snapshot을 프로세스 재시작 뒤에도 보존한다.

CREATE TABLE onmaru.catalog_screen_hanok_placements (
    place_id varchar NOT NULL,
    media_type varchar NOT NULL,
    work_title varchar NOT NULL,
    subtitle text,
    tags jsonb NOT NULL DEFAULT '[]'::jsonb,
    source_url text NOT NULL,
    source_title text,
    published_at timestamptz NOT NULL,
    PRIMARY KEY (place_id, media_type, work_title),
    CONSTRAINT catalog_screen_hanok_placements_source_url_ck CHECK (btrim(source_url) <> ''),
    CONSTRAINT catalog_screen_hanok_placements_media_type_ck CHECK (media_type IN ('K_DRAMA', 'CINEMA', 'KPOP')),
    CONSTRAINT catalog_screen_hanok_placements_tags_array_ck CHECK (jsonb_typeof(tags) = 'array')
);

CREATE INDEX catalog_screen_hanok_placements_published_at_idx
    ON onmaru.catalog_screen_hanok_placements (published_at DESC, place_id);

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '019', '343', 343, 'Persist published screen-hanok placement snapshots'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

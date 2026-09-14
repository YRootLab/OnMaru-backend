-- onmaru-checksum: d06-v008-20260915
-- Issue: #79 D06 Odii spot, story, language, transcript revision schema.

CREATE TYPE onmaru.audio_status AS ENUM (
    'ACTIVE',
    'HIDDEN',
    'DELETED'
);

CREATE TABLE onmaru.audio_odii_spots (
    id uuid PRIMARY KEY,
    provider varchar NOT NULL,
    tid varchar NOT NULL,
    tlid varchar NOT NULL,
    lang_code varchar NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT audio_odii_spots_provider_tid_tlid_uq UNIQUE (provider, tid, tlid),
    CONSTRAINT audio_odii_spots_provider_ck CHECK (btrim(provider) <> ''),
    CONSTRAINT audio_odii_spots_tid_ck CHECK (btrim(tid) <> ''),
    CONSTRAINT audio_odii_spots_tlid_ck CHECK (btrim(tlid) <> ''),
    CONSTRAINT audio_odii_spots_lang_code_ck CHECK (btrim(lang_code) <> '')
);

CREATE TABLE onmaru.audio_odii_stories (
    id uuid PRIMARY KEY,
    spot_id uuid NOT NULL REFERENCES onmaru.audio_odii_spots (id),
    provider varchar NOT NULL,
    stid varchar NOT NULL,
    stlid varchar NOT NULL,
    lang_code varchar NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT audio_odii_stories_provider_stid_stlid_uq UNIQUE (provider, stid, stlid),
    CONSTRAINT audio_odii_stories_provider_ck CHECK (btrim(provider) <> ''),
    CONSTRAINT audio_odii_stories_stid_ck CHECK (btrim(stid) <> ''),
    CONSTRAINT audio_odii_stories_stlid_ck CHECK (btrim(stlid) <> ''),
    CONSTRAINT audio_odii_stories_lang_code_ck CHECK (btrim(lang_code) <> '')
);

CREATE INDEX audio_odii_stories_spot_id_idx
    ON onmaru.audio_odii_stories (spot_id);

CREATE TABLE onmaru.audio_spot_versions (
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id),
    spot_id uuid NOT NULL REFERENCES onmaru.audio_odii_spots (id),
    title varchar NOT NULL,
    address text,
    location geography(Point, 4326),
    status onmaru.audio_status NOT NULL,
    hash varchar NOT NULL,
    PRIMARY KEY (revision_id, spot_id),
    CONSTRAINT audio_spot_versions_title_ck CHECK (btrim(title) <> ''),
    CONSTRAINT audio_spot_versions_hash_ck CHECK (btrim(hash) <> '')
);

CREATE INDEX audio_spot_versions_location_gix
    ON onmaru.audio_spot_versions USING gist (location);

CREATE TABLE onmaru.audio_story_versions (
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id),
    story_id uuid NOT NULL REFERENCES onmaru.audio_odii_stories (id),
    spot_id uuid NOT NULL REFERENCES onmaru.audio_odii_spots (id),
    title varchar NOT NULL,
    script text,
    audio_url text,
    image_url text,
    duration_seconds integer,
    status onmaru.audio_status NOT NULL,
    hash varchar NOT NULL,
    PRIMARY KEY (revision_id, story_id),
    CONSTRAINT audio_story_versions_revision_spot_fkey
        FOREIGN KEY (revision_id, spot_id)
        REFERENCES onmaru.audio_spot_versions (revision_id, spot_id),
    CONSTRAINT audio_story_versions_title_ck CHECK (btrim(title) <> ''),
    CONSTRAINT audio_story_versions_duration_seconds_ck CHECK (
        duration_seconds IS NULL OR duration_seconds >= 0
    ),
    CONSTRAINT audio_story_versions_hash_ck CHECK (btrim(hash) <> '')
);

CREATE INDEX audio_story_versions_revision_spot_idx
    ON onmaru.audio_story_versions (revision_id, spot_id);

CREATE TABLE onmaru.audio_subtitle_lines (
    revision_id uuid NOT NULL,
    story_id uuid NOT NULL,
    position integer NOT NULL,
    text text NOT NULL,
    start_seconds numeric,
    timing_mode varchar NOT NULL,
    PRIMARY KEY (revision_id, story_id, position),
    CONSTRAINT audio_subtitle_lines_story_version_fkey
        FOREIGN KEY (revision_id, story_id)
        REFERENCES onmaru.audio_story_versions (revision_id, story_id),
    CONSTRAINT audio_subtitle_lines_position_ck CHECK (position >= 0),
    CONSTRAINT audio_subtitle_lines_text_ck CHECK (btrim(text) <> ''),
    CONSTRAINT audio_subtitle_lines_start_seconds_ck CHECK (
        start_seconds IS NULL OR start_seconds >= 0
    ),
    CONSTRAINT audio_subtitle_lines_timing_mode_ck CHECK (
        timing_mode IN ('OFFICIAL', 'ESTIMATED', 'NONE')
    )
);

CREATE TABLE onmaru.audio_place_odii_links (
    place_id uuid NOT NULL REFERENCES onmaru.catalog_place_identity (id),
    spot_id uuid NOT NULL REFERENCES onmaru.audio_odii_spots (id),
    match_method varchar NOT NULL,
    confidence numeric,
    verified_at timestamptz,
    PRIMARY KEY (place_id, spot_id),
    CONSTRAINT audio_place_odii_links_match_method_ck CHECK (btrim(match_method) <> ''),
    CONSTRAINT audio_place_odii_links_confidence_ck CHECK (
        confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    ),
    CONSTRAINT audio_place_odii_links_verified_method_ck CHECK (
        match_method <> 'NAME_DISTANCE_ONLY' OR verified_at IS NOT NULL
    )
);

CREATE INDEX audio_place_odii_links_spot_id_idx
    ON onmaru.audio_place_odii_links (spot_id);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '008',
    'D06',
    79,
    'Odii audio spot, story, version, subtitle, and place link schema'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

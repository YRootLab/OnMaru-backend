-- onmaru-checksum: content-tags-v013-20260917
-- Issue: #208 Content tag extraction, projection storage, and operator override schema.

CREATE TYPE onmaru.content_tag_source AS ENUM (
    'GENERATED',
    'PINNED',
    'OPERATOR'
);

CREATE TYPE onmaru.content_tag_override_target AS ENUM (
    'PLACE',
    'ODII_STORY'
);

CREATE TYPE onmaru.content_tag_override_action AS ENUM (
    'PIN',
    'HIDE'
);

CREATE TABLE onmaru.catalog_place_content_tag_versions (
    revision_id uuid NOT NULL REFERENCES onmaru.catalog_dataset_revisions (id),
    place_id uuid NOT NULL REFERENCES onmaru.catalog_place_identity (id),
    position integer NOT NULL,
    label varchar NOT NULL,
    score numeric NOT NULL,
    source onmaru.content_tag_source NOT NULL,
    algorithm_version varchar NOT NULL,
    source_hash varchar NOT NULL,
    generated_at timestamptz NOT NULL,
    PRIMARY KEY (revision_id, place_id, position),
    CONSTRAINT catalog_place_content_tag_versions_place_version_fkey
        FOREIGN KEY (revision_id, place_id)
        REFERENCES onmaru.catalog_place_versions (revision_id, place_id),
    CONSTRAINT catalog_place_content_tag_versions_position_ck CHECK (position >= 0 AND position < 7),
    CONSTRAINT catalog_place_content_tag_versions_label_ck CHECK (btrim(label) <> ''),
    CONSTRAINT catalog_place_content_tag_versions_score_ck CHECK (score >= 0),
    CONSTRAINT catalog_place_content_tag_versions_algorithm_version_ck CHECK (btrim(algorithm_version) <> ''),
    CONSTRAINT catalog_place_content_tag_versions_source_hash_ck CHECK (btrim(source_hash) <> '')
);

CREATE UNIQUE INDEX catalog_place_content_tag_versions_label_uq
    ON onmaru.catalog_place_content_tag_versions (revision_id, place_id, lower(label));

CREATE INDEX catalog_place_content_tag_versions_lookup_idx
    ON onmaru.catalog_place_content_tag_versions (label, revision_id);

CREATE TABLE onmaru.audio_story_content_tag_versions (
    revision_id uuid NOT NULL,
    story_id uuid NOT NULL,
    position integer NOT NULL,
    label varchar NOT NULL,
    score numeric NOT NULL,
    source onmaru.content_tag_source NOT NULL,
    algorithm_version varchar NOT NULL,
    source_hash varchar NOT NULL,
    generated_at timestamptz NOT NULL,
    PRIMARY KEY (revision_id, story_id, position),
    CONSTRAINT audio_story_content_tag_versions_story_version_fkey
        FOREIGN KEY (revision_id, story_id)
        REFERENCES onmaru.audio_story_versions (revision_id, story_id),
    CONSTRAINT audio_story_content_tag_versions_position_ck CHECK (position >= 0 AND position < 7),
    CONSTRAINT audio_story_content_tag_versions_label_ck CHECK (btrim(label) <> ''),
    CONSTRAINT audio_story_content_tag_versions_score_ck CHECK (score >= 0),
    CONSTRAINT audio_story_content_tag_versions_algorithm_version_ck CHECK (btrim(algorithm_version) <> ''),
    CONSTRAINT audio_story_content_tag_versions_source_hash_ck CHECK (btrim(source_hash) <> '')
);

CREATE UNIQUE INDEX audio_story_content_tag_versions_label_uq
    ON onmaru.audio_story_content_tag_versions (revision_id, story_id, lower(label));

CREATE INDEX audio_story_content_tag_versions_lookup_idx
    ON onmaru.audio_story_content_tag_versions (label, revision_id);

CREATE TABLE onmaru.content_tag_overrides (
    id uuid PRIMARY KEY,
    target_type onmaru.content_tag_override_target NOT NULL,
    target_id uuid NOT NULL,
    label varchar NOT NULL,
    action onmaru.content_tag_override_action NOT NULL,
    reason text,
    created_by uuid REFERENCES onmaru.identity_members (id),
    created_at timestamptz NOT NULL,
    expires_at timestamptz,
    CONSTRAINT content_tag_overrides_label_ck CHECK (btrim(label) <> ''),
    CONSTRAINT content_tag_overrides_expires_at_ck CHECK (
        expires_at IS NULL OR expires_at > created_at
    )
);

CREATE UNIQUE INDEX content_tag_overrides_target_label_action_uq
    ON onmaru.content_tag_overrides (target_type, target_id, lower(label), action);

CREATE INDEX content_tag_overrides_target_idx
    ON onmaru.content_tag_overrides (target_type, target_id);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '013',
    'CONTENT_TAGS',
    208,
    'Content tag projection storage and operator override schema'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

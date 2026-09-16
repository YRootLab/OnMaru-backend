-- onmaru-checksum: a02-v010-20260916
-- Issue: #197 Odii production revision persistence and atomic place-link approval.

CREATE TABLE onmaru.audio_revision_stages (
    revision_id uuid PRIMARY KEY REFERENCES onmaru.catalog_dataset_revisions (id) ON DELETE CASCADE,
    ready boolean NOT NULL DEFAULT false,
    row_count bigint NOT NULL DEFAULT 0,
    empty_full_sync_reviewed boolean NOT NULL DEFAULT false,
    failure_code varchar,
    tombstone_count bigint NOT NULL DEFAULT 0,
    CONSTRAINT audio_revision_stages_row_count_ck CHECK (row_count >= 0),
    CONSTRAINT audio_revision_stages_tombstone_count_ck CHECK (tombstone_count >= 0),
    CONSTRAINT audio_revision_stages_ready_failure_ck CHECK (NOT ready OR failure_code IS NULL)
);

ALTER TABLE onmaru.audio_spot_versions
    ADD COLUMN source_modified_at timestamptz,
    ADD COLUMN observed boolean NOT NULL DEFAULT true,
    ADD COLUMN missing_observations integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT audio_spot_versions_missing_observations_ck
        CHECK (missing_observations >= 0);

ALTER TABLE onmaru.audio_story_versions
    ADD COLUMN transcript_provenance varchar,
    ADD COLUMN source_modified_at timestamptz,
    ADD COLUMN observed boolean NOT NULL DEFAULT true,
    ADD COLUMN missing_observations integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT audio_story_versions_missing_observations_ck
        CHECK (missing_observations >= 0);

UPDATE onmaru.audio_story_versions
SET transcript_provenance = CASE WHEN script IS NULL THEN 'MISSING' ELSE 'OFFICIAL' END;

ALTER TABLE onmaru.audio_story_versions
    ALTER COLUMN transcript_provenance SET NOT NULL,
    ADD CONSTRAINT audio_story_versions_transcript_provenance_ck
        CHECK (transcript_provenance IN ('OFFICIAL', 'MISSING'));

ALTER TABLE onmaru.audio_place_odii_links
    DROP CONSTRAINT audio_place_odii_links_verified_method_ck,
    ADD COLUMN review_status varchar;

UPDATE onmaru.audio_place_odii_links
SET review_status = CASE WHEN verified_at IS NULL THEN 'PENDING' ELSE 'APPROVED' END;

ALTER TABLE onmaru.audio_place_odii_links
    ALTER COLUMN review_status SET NOT NULL,
    ADD CONSTRAINT audio_place_odii_links_review_status_ck
        CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    ADD CONSTRAINT audio_place_odii_links_reviewed_at_ck
        CHECK ((review_status = 'PENDING') = (verified_at IS NULL));

CREATE UNIQUE INDEX audio_place_odii_links_one_approved_per_spot_uq
    ON onmaru.audio_place_odii_links (spot_id)
    WHERE review_status = 'APPROVED';

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '010',
    'A02-FOLLOWUP',
    197,
    'Odii production revision persistence and atomic place-link approval'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

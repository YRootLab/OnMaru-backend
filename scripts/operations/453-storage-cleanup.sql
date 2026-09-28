-- Issue #453 production cleanup. DESTRUCTIVE: run only after reviewing
-- 453-storage-cleanup-dry-run.sql output and receiving explicit approval.
-- Active dataset revisions and compact operations_sync_runs metadata survive.

BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10min';

CREATE TEMP TABLE inactive_catalog_revisions ON COMMIT DROP AS
SELECT revision.id
FROM onmaru.catalog_dataset_revisions revision
WHERE NOT EXISTS (
    SELECT 1
    FROM onmaru.catalog_active_datasets active
    WHERE active.revision_id = revision.id
);

-- Provider failure payload retention is disabled by policy. TRUNCATE releases
-- this relation without leaving a table-sized set of dead tuples.
TRUNCATE TABLE onmaru.operations_sync_quarantine;

UPDATE onmaru.operations_sync_runs
SET revision_id = NULL
WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);

UPDATE onmaru.catalog_dataset_revisions
SET base_revision_id = NULL
WHERE base_revision_id IN (SELECT id FROM inactive_catalog_revisions);

DELETE FROM onmaru.operations_sync_watermarks WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);
DELETE FROM onmaru.audio_revision_stages WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);
DELETE FROM onmaru.audio_story_content_tag_versions WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);
DELETE FROM onmaru.audio_subtitle_lines WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);
DELETE FROM onmaru.audio_story_versions WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);
DELETE FROM onmaru.audio_spot_versions WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);
DELETE FROM onmaru.catalog_place_content_tag_versions WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);
DELETE FROM onmaru.catalog_hanok_detail_versions WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);
DELETE FROM onmaru.catalog_place_image_versions WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);
DELETE FROM onmaru.catalog_kto_korean_info_versions WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);
DELETE FROM onmaru.catalog_kto_korean_intro_versions WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);
DELETE FROM onmaru.catalog_kto_korean_content_versions WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);
DELETE FROM onmaru.catalog_place_versions WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);
DELETE FROM onmaru.insights_concentration_observations WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);
DELETE FROM onmaru.insights_visitor_observations WHERE revision_id IN (SELECT id FROM inactive_catalog_revisions);

DELETE FROM onmaru.catalog_dataset_revisions WHERE id IN (SELECT id FROM inactive_catalog_revisions);

COMMIT;

ANALYZE onmaru.catalog_dataset_revisions;
ANALYZE onmaru.operations_sync_runs;

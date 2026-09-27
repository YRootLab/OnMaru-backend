-- Issue #453 production cleanup preview. Read-only: safe to run before approval.
-- The active revision of every dataset is protected.

WITH inactive AS (
    SELECT revision.id, revision.dataset, revision.status, revision.fetched_at
    FROM onmaru.catalog_dataset_revisions revision
    WHERE NOT EXISTS (
        SELECT 1
        FROM onmaru.catalog_active_datasets active
        WHERE active.revision_id = revision.id
    )
)
SELECT dataset, status, count(*) AS revision_count,
       min(fetched_at) AS oldest_fetched_at,
       max(fetched_at) AS newest_fetched_at
FROM inactive
GROUP BY dataset, status
ORDER BY dataset, status;

WITH inactive AS (
    SELECT revision.id
    FROM onmaru.catalog_dataset_revisions revision
    WHERE NOT EXISTS (
        SELECT 1 FROM onmaru.catalog_active_datasets active
        WHERE active.revision_id = revision.id
    )
)
SELECT relation, rows_to_delete
FROM (
    SELECT 'operations_sync_quarantine' AS relation,
           (SELECT count(*) FROM onmaru.operations_sync_quarantine) AS rows_to_delete
    UNION ALL SELECT 'catalog_kto_korean_content_versions', count(*)
      FROM onmaru.catalog_kto_korean_content_versions WHERE revision_id IN (SELECT id FROM inactive)
    UNION ALL SELECT 'catalog_place_versions', count(*)
      FROM onmaru.catalog_place_versions WHERE revision_id IN (SELECT id FROM inactive)
    UNION ALL SELECT 'catalog_place_image_versions', count(*)
      FROM onmaru.catalog_place_image_versions WHERE revision_id IN (SELECT id FROM inactive)
    UNION ALL SELECT 'catalog_place_content_tag_versions', count(*)
      FROM onmaru.catalog_place_content_tag_versions WHERE revision_id IN (SELECT id FROM inactive)
    UNION ALL SELECT 'audio_story_versions', count(*)
      FROM onmaru.audio_story_versions WHERE revision_id IN (SELECT id FROM inactive)
    UNION ALL SELECT 'audio_subtitle_lines', count(*)
      FROM onmaru.audio_subtitle_lines WHERE revision_id IN (SELECT id FROM inactive)
) estimate
ORDER BY rows_to_delete DESC;

SELECT relname,
       pg_size_pretty(pg_total_relation_size(relid)) AS total_size
FROM pg_catalog.pg_statio_user_tables
WHERE schemaname = 'onmaru'
  AND relname IN (
      'operations_sync_quarantine',
      'catalog_kto_korean_content_versions',
      'catalog_place_versions',
      'catalog_place_image_versions',
      'catalog_place_content_tag_versions',
      'audio_story_versions',
      'audio_subtitle_lines'
  )
ORDER BY pg_total_relation_size(relid) DESC;

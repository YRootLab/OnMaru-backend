-- Remove only #691 synthetic selected-discovery rows before a real W3 run.
\set ON_ERROR_STOP on
BEGIN;
DO $$ BEGIN
  IF current_database() <> 'onmaru_staging' THEN RAISE EXCEPTION 'staging DB required'; END IF;
  IF EXISTS (SELECT 1 FROM onmaru.selected_discovery_active
             WHERE revision_id NOT IN ('69100000-0000-4000-8000-000000000001'::uuid,
                                       '69100000-0000-4000-8000-000000000002'::uuid)) THEN
    RAISE EXCEPTION 'real discovery active exists; fixture cleanup refused';
  END IF;
  IF EXISTS (SELECT 1 FROM onmaru.selected_discovery_revisions
             WHERE base_revision_id IN ('69100000-0000-4000-8000-000000000001'::uuid,
                                        '69100000-0000-4000-8000-000000000002'::uuid)
               AND id NOT IN ('69100000-0000-4000-8000-000000000001'::uuid,
                              '69100000-0000-4000-8000-000000000002'::uuid)) THEN
    RAISE EXCEPTION 'a non-fixture revision depends on fixture';
  END IF;
END $$;
DELETE FROM onmaru.selected_discovery_active
 WHERE revision_id IN ('69100000-0000-4000-8000-000000000001','69100000-0000-4000-8000-000000000002');
DELETE FROM onmaru.selected_discovery_public_items
 WHERE revision_id IN ('69100000-0000-4000-8000-000000000001','69100000-0000-4000-8000-000000000002');
DELETE FROM onmaru.selected_discovery_candidates
 WHERE revision_id IN ('69100000-0000-4000-8000-000000000001','69100000-0000-4000-8000-000000000002');
DELETE FROM onmaru.selected_discovery_approvals WHERE content_id IN ('staging-691-1','staging-691-2','staging-691-3');
DELETE FROM onmaru.selected_discovery_revisions WHERE id='69100000-0000-4000-8000-000000000002';
DELETE FROM onmaru.selected_discovery_revisions WHERE id='69100000-0000-4000-8000-000000000001';
DELETE FROM onmaru.selected_discovery_runs
 WHERE id IN ('69100000-0000-4000-8000-000000000001','69100000-0000-4000-8000-000000000002');
COMMIT;

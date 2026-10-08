-- Staging only. Use with psql -X -v ON_ERROR_STOP=1 -v expected_active=... -v target_revision=...
-- Requires the staging write operator login. No legacy catalog pointer is touched.
BEGIN;
SELECT set_config('onmaru.rollback.expected', :'expected_active', true);
SELECT set_config('onmaru.rollback.target', :'target_revision', true);
SELECT pg_advisory_xact_lock(hashtext('onmaru.selected_discovery_active'));
DO $rollback$
DECLARE current_id uuid;
BEGIN
    SELECT revision_id INTO current_id FROM onmaru.selected_discovery_active WHERE singleton=true FOR UPDATE;
    IF current_id IS DISTINCT FROM current_setting('onmaru.rollback.expected')::uuid THEN
        RAISE EXCEPTION 'discovery active revision changed: %', current_id;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM onmaru.selected_discovery_revisions
                   WHERE id=current_setting('onmaru.rollback.target')::uuid AND status='PUBLISHED') THEN
        RAISE EXCEPTION 'rollback target is not a published revision';
    END IF;
    UPDATE onmaru.selected_discovery_active
       SET revision_id=current_setting('onmaru.rollback.target')::uuid, activated_at=now()
     WHERE singleton=true AND revision_id=current_id;
END $rollback$;
COMMIT;
SELECT revision_id FROM onmaru.selected_discovery_active WHERE singleton=true;

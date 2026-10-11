-- Explicit staging-only cursor/rollback rehearsal data. Never count as a live pilot.
\set ON_ERROR_STOP on
BEGIN;
DO $$ BEGIN
  IF current_database() <> 'onmaru_staging' THEN RAISE EXCEPTION 'staging DB required'; END IF;
  IF EXISTS (SELECT 1 FROM onmaru.selected_discovery_active
             WHERE revision_id NOT IN ('69100000-0000-4000-8000-000000000001'::uuid,
                                       '69100000-0000-4000-8000-000000000002'::uuid)) THEN
    RAISE EXCEPTION 'real discovery active exists; fixture refused';
  END IF;
  IF EXISTS (SELECT 1 FROM onmaru.selected_discovery_revisions
             WHERE id NOT IN ('69100000-0000-4000-8000-000000000001'::uuid,
                              '69100000-0000-4000-8000-000000000002'::uuid)) THEN
    RAISE EXCEPTION 'real discovery revision exists; fixture refused';
  END IF;
END $$;
INSERT INTO onmaru.selected_discovery_runs(id,due_at,status,finished_at) VALUES
 ('69100000-0000-4000-8000-000000000001','2026-10-09T00:00:00Z','PUBLISHED',now()),
 ('69100000-0000-4000-8000-000000000002','2026-10-10T00:00:00Z','PUBLISHED',now())
ON CONFLICT (id) DO NOTHING;
INSERT INTO onmaru.selected_discovery_revisions
 (id,base_revision_id,status,policy_version,hash_schema_version,added_count,changed_count,unchanged_count,
  missing_count,quarantine_count,approved_count,detail_requests,counts_by_region,counts_by_role,published_at) VALUES
 ('69100000-0000-4000-8000-000000000001',NULL,'PUBLISHED','staging-fixture','staging-fixture',2,0,0,0,0,2,0,'{"STG":2}','{"CORE_TRADITIONAL_PLACE":2}',now()),
 ('69100000-0000-4000-8000-000000000002','69100000-0000-4000-8000-000000000001','PUBLISHED','staging-fixture','staging-fixture',1,0,2,0,0,3,0,'{"STG":3}','{"CORE_TRADITIONAL_PLACE":3}',now())
ON CONFLICT (id) DO NOTHING;
INSERT INTO onmaru.selected_discovery_approvals
 (content_id,list_hash,detail_hash,role,source_fingerprint,detail_reviewed,rights_reviewed,evidence_ref,approved_by,approved_at)
SELECT 'staging-691-' || n, repeat('a',64),repeat('b',64),'CORE_TRADITIONAL_PLACE',
       'staging-fixture-' || n,true,true,'staging-fixture','staging-fixture',now()
FROM generate_series(1,3) n WHERE true ON CONFLICT (content_id) DO NOTHING;
INSERT INTO onmaru.selected_discovery_candidates
 (revision_id,content_id,raw,list_hash,detail_hash,hash_schema_version,policy_version,decision,role,reason_code,diff_status)
SELECT revision_id,'staging-691-' || n,
       jsonb_build_object('fields',jsonb_build_object(
           'contentid','staging-691-' || n,'title','합성 선택 장소 ' || n,
           'overview','staging cursor 전용 합성 자료','mapx','127.0','mapy','37.5')),
       repeat('a',64),repeat('b',64),'staging-fixture','staging-fixture','INCLUDE',
       'CORE_TRADITIONAL_PLACE','STAGING_FIXTURE','UNCHANGED'
FROM (VALUES ('69100000-0000-4000-8000-000000000001'::uuid,2),
             ('69100000-0000-4000-8000-000000000002'::uuid,3)) revision(revision_id,max_n)
CROSS JOIN LATERAL generate_series(1,max_n) n
WHERE true
ON CONFLICT (revision_id,content_id) DO NOTHING;
INSERT INTO onmaru.selected_discovery_public_items(revision_id,content_id,place_id,role,region_code,raw)
SELECT c.revision_id,c.content_id,
       ('54500000-0000-4000-8000-' || lpad((20+right(c.content_id,1)::integer)::text,12,'0'))::uuid,
       c.role,'STG',c.raw
FROM onmaru.selected_discovery_candidates c
WHERE c.revision_id IN ('69100000-0000-4000-8000-000000000001','69100000-0000-4000-8000-000000000002')
ON CONFLICT (revision_id,content_id) DO NOTHING;
INSERT INTO onmaru.selected_discovery_active(singleton,revision_id,activated_at)
VALUES(true,'69100000-0000-4000-8000-000000000002',now())
ON CONFLICT (singleton) DO NOTHING;
COMMIT;

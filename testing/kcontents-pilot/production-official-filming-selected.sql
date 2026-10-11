-- #691: publish the 14 reviewed official filming places alongside the existing three.
-- Run only after production-official-filming-reviewed.sql succeeds.
-- The active pointer moves in the final statement; default is ROLLBACK.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '3s';

CREATE TEMP TABLE new_selected ON COMMIT DROP AS
SELECT DISTINCT ON (r.place_id)
  s.contentid AS content_id, r.place_id,
  COALESCE(s.areacode, CASE s.contentid WHEN '2603509' THEN '35' WHEN '2755023' THEN '39' END) AS region_code,
  s.raw_hash, s.modifiedtime,
  jsonb_build_object('provider','kto-tourapi-korean',
    'operation','curated-existing-catalog', 'sourceRevision',s.revision_id,
    'fields',jsonb_build_object('contentid',s.contentid,'title',s.title,
      'addr1',s.addr1,'mapx',s.mapx,'mapy',s.mapy,'lclsSystm3',s.lcls_systm3)) AS raw
FROM onmaru.k_content_place_relations r
JOIN onmaru.k_content_relation_evidence e ON e.relation_id=r.id AND e.status='VERIFIED'
  AND e.canonical_url='https://www.data.go.kr/data/15111405/fileData.do'
JOIN onmaru.catalog_place_versions p ON p.place_id=r.place_id AND p.status='ACTIVE'
JOIN onmaru.catalog_active_datasets a ON a.dataset='kto-korean-tour' AND a.revision_id=p.revision_id
JOIN onmaru.catalog_kto_korean_content_versions s ON s.revision_id=p.revision_id
  AND s.source_ref_id=p.source_ref_id
WHERE r.status='AUTO_VERIFIED' AND r.rule_version='official-filming-exact-v1'
ORDER BY r.place_id,s.contentid;

DO $$ BEGIN
  IF (SELECT count(*) FROM new_selected) <> 14 THEN
    RAISE EXCEPTION 'Expected exactly 14 distinct reviewed places'; END IF;
  IF (SELECT revision_id FROM onmaru.selected_discovery_active WHERE singleton)
      <> md5('onmaru:pilot691:selected:2026-10-11')::uuid THEN
    RAISE EXCEPTION 'Active selected revision changed'; END IF;
  IF EXISTS (SELECT 1 FROM new_selected n JOIN onmaru.selected_discovery_public_visible v
       ON v.content_id=n.content_id OR v.place_id=n.place_id) THEN
    RAISE EXCEPTION 'New place overlaps active selected data'; END IF;
  IF EXISTS (SELECT 1 FROM onmaru.selected_discovery_revisions
       WHERE id=md5('onmaru:official691:selected:2026-10-12')::uuid) THEN
    RAISE EXCEPTION 'Selected revision already exists'; END IF;
END $$;

INSERT INTO onmaru.selected_discovery_runs
  (id,due_at,status,detail_requests,started_at,finished_at)
VALUES (md5('onmaru:official691:selected:2026-10-12')::uuid,
  '2026-10-12T14:00:00Z','PUBLISHED',0,now(),now());

INSERT INTO onmaru.selected_discovery_revisions
  (id,base_revision_id,status,policy_version,hash_schema_version,
   added_count,changed_count,unchanged_count,missing_count,quarantine_count,
   approved_count,detail_requests,counts_by_region,counts_by_role,published_at)
SELECT md5('onmaru:official691:selected:2026-10-12')::uuid,
  md5('onmaru:pilot691:selected:2026-10-11')::uuid,'PUBLISHED',
  'curated-official-filming-691-v1','catalog-fields-sha256-v1',
  14,0,3,0,0,17,0,
  (SELECT jsonb_object_agg(region_code,n) FROM
    (SELECT region_code,count(*) AS n FROM
      (SELECT region_code FROM new_selected UNION ALL
       SELECT region_code FROM onmaru.selected_discovery_public_visible) all_places
      GROUP BY region_code) grouped),
  '{"CORE_TRADITIONAL_PLACE":17}'::jsonb,now();

INSERT INTO onmaru.selected_discovery_candidates
  (revision_id,content_id,raw,list_hash,detail_hash,hash_schema_version,
   policy_version,decision,role,reason_code,diff_status,modifiedtime)
SELECT md5('onmaru:official691:selected:2026-10-12')::uuid,
  c.content_id,c.raw,c.list_hash,c.detail_hash,c.hash_schema_version,
  'curated-official-filming-691-v1',c.decision,c.role,
  'CARRIED_FORWARD_691','UNCHANGED',c.modifiedtime
FROM onmaru.selected_discovery_candidates c
WHERE c.revision_id=md5('onmaru:pilot691:selected:2026-10-11')::uuid;

INSERT INTO onmaru.selected_discovery_candidates
  (revision_id,content_id,raw,list_hash,detail_hash,hash_schema_version,
   policy_version,decision,role,reason_code,diff_status,modifiedtime)
SELECT md5('onmaru:official691:selected:2026-10-12')::uuid,
  content_id,raw,
  encode(sha256(convert_to(raw::text,'UTF8')),'hex'),
  encode(sha256(convert_to(raw::text||':'||raw_hash,'UTF8')),'hex'),
  'catalog-fields-sha256-v1','curated-official-filming-691-v1',
  'INCLUDE','CORE_TRADITIONAL_PLACE','OFFICIAL_DATASET_REVIEWED_691',
  'ADDED',modifiedtime
FROM new_selected;

INSERT INTO onmaru.selected_discovery_approvals
  (content_id,list_hash,detail_hash,role,source_fingerprint,detail_reviewed,
   rights_reviewed,evidence_ref,approved_by,approved_at)
SELECT n.content_id,c.list_hash,c.detail_hash,'CORE_TRADITIONAL_PLACE',
  n.raw_hash,true,true,'https://www.data.go.kr/data/15111405/fileData.do',
  'codex-issue-691',now()
FROM new_selected n JOIN onmaru.selected_discovery_candidates c
  ON c.revision_id=md5('onmaru:official691:selected:2026-10-12')::uuid
  AND c.content_id=n.content_id;

INSERT INTO onmaru.selected_discovery_approval_audit
  (id,content_id,action,actor,list_hash,detail_hash,evidence_ref)
SELECT md5('onmaru:official691:approval:'||n.content_id)::uuid,
  n.content_id,'APPROVE','codex-issue-691',c.list_hash,c.detail_hash,
  'https://www.data.go.kr/data/15111405/fileData.do'
FROM new_selected n JOIN onmaru.selected_discovery_candidates c
  ON c.revision_id=md5('onmaru:official691:selected:2026-10-12')::uuid
  AND c.content_id=n.content_id;

INSERT INTO onmaru.selected_discovery_public_items
  (revision_id,content_id,place_id,role,region_code,raw)
SELECT md5('onmaru:official691:selected:2026-10-12')::uuid,
  p.content_id,p.place_id,p.role,p.region_code,p.raw
FROM onmaru.selected_discovery_public_items p
WHERE p.revision_id=md5('onmaru:pilot691:selected:2026-10-11')::uuid;
INSERT INTO onmaru.selected_discovery_public_items
  (revision_id,content_id,place_id,role,region_code,raw)
SELECT md5('onmaru:official691:selected:2026-10-12')::uuid,
  content_id,place_id,'CORE_TRADITIONAL_PLACE',region_code,raw
FROM new_selected;

UPDATE onmaru.selected_discovery_active
SET revision_id=md5('onmaru:official691:selected:2026-10-12')::uuid,
    activated_at=now()
WHERE singleton AND revision_id=md5('onmaru:pilot691:selected:2026-10-11')::uuid;

DO $$ BEGIN IF (SELECT count(*) FROM onmaru.selected_discovery_public_visible) <> 17
THEN RAISE EXCEPTION 'Expected 17 visible places'; END IF; END $$;
SELECT count(*) AS visible_places FROM onmaru.selected_discovery_public_visible;
\if :{?apply}
COMMIT;
\else
ROLLBACK;
\endif

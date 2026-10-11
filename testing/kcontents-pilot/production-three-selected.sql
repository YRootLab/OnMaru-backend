-- #691: one-time curated discovery revision from the existing active production catalog.
-- Use only for the three independently sourced filming relations in
-- production-three-relations.sql, after V047 and before a normal W3 publication.
-- Only title, address, coordinates and taxonomy are copied; no image or article body.
-- Default is ROLLBACK. Add -v apply=1 only after inspecting the dry-run rows.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL statement_timeout = '15s';
SET LOCAL lock_timeout = '3s';

CREATE TEMP TABLE pilot_selected ON COMMIT DROP AS
WITH expected(content_id, place_id, address_prefix, region_code, evidence_url) AS (
  VALUES
    ('126998', '686c9614-5d97-3b13-b946-7691492c3021'::uuid,
     '경상북도 안동시 길안면 묵계하리길 42', '35',
     'https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=d4594ee6-ca20-4083-87fa-f22670b1c5f2'),
    ('125800', 'f112eb98-e94f-3c3c-ba97-ae18469e9dbe'::uuid,
     '강원특별자치도 강릉시 운정길 63', '32',
     'https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=50441e4a-309d-4fcc-bcbd-81cb2affcef6'),
    ('126397', 'b398e867-8ab8-38cd-8982-982b2839b4df'::uuid,
     '전북특별자치도 남원시 요천로 1447', '37',
     'https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=2408dce3-d53c-43d8-8cd3-915488da62d1')
)
SELECT e.content_id, e.place_id, e.region_code, e.evidence_url,
       s.raw_hash, s.modifiedtime,
       jsonb_build_object(
         'provider', 'kto-tourapi-korean',
         'operation', 'curated-existing-catalog',
         'sourceRevision', s.revision_id,
         'fields', jsonb_build_object(
           'contentid', s.contentid, 'title', s.title, 'addr1', s.addr1,
           'mapx', s.mapx, 'mapy', s.mapy, 'lclsSystm3', s.lcls_systm3
         )
       ) AS raw
FROM expected e
JOIN onmaru.catalog_kto_korean_content_versions s
  ON s.contentid = e.content_id AND s.addr1 LIKE e.address_prefix || '%'
JOIN onmaru.catalog_active_datasets a
  ON a.dataset = 'kto-korean-tour' AND a.revision_id = s.revision_id
JOIN onmaru.catalog_place_versions p
  ON p.revision_id = s.revision_id AND p.source_ref_id = s.source_ref_id
  AND p.place_id = e.place_id AND p.status = 'ACTIVE'
JOIN onmaru.catalog_place_public_ids pid
  ON pid.place_id = p.place_id AND pid.public_id = 'p-tourapi-' || e.content_id;

DO $$
BEGIN
  IF (SELECT count(*) FROM pilot_selected) <> 3
     OR (SELECT count(DISTINCT place_id) FROM pilot_selected) <> 3 THEN
    RAISE EXCEPTION 'Expected three distinct active production catalog places';
  END IF;
  IF EXISTS (SELECT 1 FROM onmaru.selected_discovery_active) THEN
    RAISE EXCEPTION 'An active selected revision already exists; do not replace it';
  END IF;
  IF EXISTS (SELECT 1 FROM onmaru.selected_discovery_revisions
             WHERE id = md5('onmaru:pilot691:selected:2026-10-11')::uuid) THEN
    RAISE EXCEPTION 'Pilot revision already exists; inspect before proceeding';
  END IF;
END $$;

INSERT INTO onmaru.selected_discovery_runs
  (id, due_at, status, detail_requests, started_at, finished_at)
VALUES (
  md5('onmaru:pilot691:selected:2026-10-11')::uuid,
  '2026-10-11T14:00:00Z', 'PUBLISHED', 0, now(), now()
);

INSERT INTO onmaru.selected_discovery_revisions
  (id, status, policy_version, hash_schema_version, added_count, changed_count,
   unchanged_count, missing_count, quarantine_count, approved_count, detail_requests,
   counts_by_region, counts_by_role, published_at)
VALUES (
  md5('onmaru:pilot691:selected:2026-10-11')::uuid, 'PUBLISHED',
  'curated-existing-catalog-691-v1', 'catalog-fields-sha256-v1',
  3, 0, 0, 0, 0, 3, 0,
  '{"32":1,"35":1,"37":1}'::jsonb,
  '{"CORE_TRADITIONAL_PLACE":3}'::jsonb, now()
);

INSERT INTO onmaru.selected_discovery_candidates
  (revision_id, content_id, raw, list_hash, detail_hash, hash_schema_version,
   policy_version, decision, role, reason_code, diff_status, modifiedtime)
SELECT md5('onmaru:pilot691:selected:2026-10-11')::uuid, content_id, raw,
       encode(sha256(convert_to(raw::text, 'UTF8')), 'hex'),
       encode(sha256(convert_to(raw::text || ':' || raw_hash, 'UTF8')), 'hex'),
       'catalog-fields-sha256-v1', 'curated-existing-catalog-691-v1',
       'INCLUDE', 'CORE_TRADITIONAL_PLACE',
       'MANUAL_EXISTING_CATALOG_691', 'ADDED', modifiedtime
FROM pilot_selected;

-- The approving actor is the agent performing this bounded inspection, not a
-- claimed human review. Rights review covers only the copied factual fields.
INSERT INTO onmaru.selected_discovery_approvals
  (content_id, list_hash, detail_hash, role, source_fingerprint,
   detail_reviewed, rights_reviewed, evidence_ref, approved_by, approved_at)
SELECT p.content_id, c.list_hash, c.detail_hash, 'CORE_TRADITIONAL_PLACE',
       p.raw_hash, true, true, p.evidence_url, 'codex-issue-691', now()
FROM pilot_selected p JOIN onmaru.selected_discovery_candidates c
  ON c.revision_id = md5('onmaru:pilot691:selected:2026-10-11')::uuid
  AND c.content_id = p.content_id;

INSERT INTO onmaru.selected_discovery_approval_audit
  (id, content_id, action, actor, list_hash, detail_hash, evidence_ref)
SELECT md5('onmaru:pilot691:approval:' || p.content_id)::uuid,
       p.content_id, 'APPROVE', 'codex-issue-691',
       c.list_hash, c.detail_hash, p.evidence_url
FROM pilot_selected p JOIN onmaru.selected_discovery_candidates c
  ON c.revision_id = md5('onmaru:pilot691:selected:2026-10-11')::uuid
  AND c.content_id = p.content_id;

INSERT INTO onmaru.selected_discovery_public_items
  (revision_id, content_id, place_id, role, region_code, raw)
SELECT md5('onmaru:pilot691:selected:2026-10-11')::uuid,
       content_id, place_id, 'CORE_TRADITIONAL_PLACE', region_code, raw
FROM pilot_selected;

INSERT INTO onmaru.selected_discovery_active (singleton, revision_id, activated_at)
VALUES (true, md5('onmaru:pilot691:selected:2026-10-11')::uuid, now());

SELECT p.content_id, p.place_id, p.region_code, p.raw->'fields'->>'title' AS title
FROM onmaru.selected_discovery_public_visible p ORDER BY p.content_id;

\if :{?apply}
COMMIT;
\else
ROLLBACK;
\endif

-- Run with psql -X -q -A -t -v ON_ERROR_STOP=1 -v pilot_reason=... -f read-only-evidence.sql
-- The login must have SELECT only. Never use the migration or runtime writer login.
BEGIN READ ONLY;
WITH cohort AS (
    SELECT id, place_id, status, created_at, completed_at
    FROM onmaru.k_content_research_jobs WHERE reason = :'pilot_reason'
), sources AS (
    SELECT DISTINCT e.job_id, e.canonical_url, j.place_id
    FROM onmaru.k_content_research_evidence e JOIN cohort j ON j.id=e.job_id
), published_relations AS (
    SELECT DISTINCT r.id, r.place_id, r.k_content_id
    FROM onmaru.k_content_public_relations r
    JOIN onmaru.k_content_relation_evidence e ON e.relation_id=r.id AND e.status='VERIFIED'
    JOIN sources s ON s.place_id=r.place_id AND s.canonical_url=e.canonical_url
), validation AS (
    SELECT v.* FROM onmaru.k_content_validation_runs v JOIN cohort j ON j.id=v.job_id
), timed AS (
    SELECT extract(epoch FROM max(coalesce(completed_at,now()))-min(created_at)) AS seconds FROM cohort
)
SELECT jsonb_build_object(
    'pilotReason', :'pilot_reason',
    'totalJobs', (SELECT count(*) FROM cohort),
    'succeededJobs', (SELECT count(*) FROM cohort WHERE status='SUCCEEDED'),
    'failedJobs', (SELECT count(*) FROM cohort WHERE status='FAILED'),
    'quarantinedJobs', (SELECT count(*) FROM cohort WHERE status='QUARANTINED'),
    'jobsWithEvidence', (SELECT count(DISTINCT job_id) FROM sources),
    'validationRuns', (SELECT count(*) FROM validation),
    'reviewRequiredRuns', (SELECT count(*) FROM validation WHERE status='REVIEW_REQUIRED'),
    'publicRelations', (SELECT count(*) FROM published_relations),
    'publicRelationIds', coalesce((SELECT jsonb_agg(id ORDER BY id) FROM published_relations),'[]'::jsonb),
    'publicPlaces', (SELECT count(DISTINCT place_id) FROM published_relations),
    'distinctRegions', (SELECT count(DISTINCT region_code) FROM onmaru.selected_discovery_public_visible p JOIN published_relations r ON r.place_id=p.place_id),
    'distinctWorks', (SELECT count(DISTINCT k_content_id) FROM published_relations),
    'durationSeconds', (SELECT seconds FROM timed),
    'sampledAt', now()
)::text;
ROLLBACK;

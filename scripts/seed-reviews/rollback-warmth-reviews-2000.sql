-- ====================================================================
-- Rollback Warmth Reviews 2000 Seed Script (Issue #765)
-- Safely removes only the 2,000 seeded reviews (0501~2500) and members (0056~0205)
-- ====================================================================
\set ON_ERROR_STOP on
BEGIN;

-- 1. Delete 2,000 reviews
DELETE FROM onmaru.community_visit_reviews 
WHERE id >= '54500700-0000-4000-9000-000000000501'::uuid 
  AND id <= '54500700-0000-4000-9000-000000002500'::uuid;

-- 2. Delete 150 virtual members and profiles
DELETE FROM onmaru.identity_member_profiles 
WHERE member_id >= '54500700-0000-4000-8000-000000000056'::uuid 
  AND member_id <= '54500700-0000-4000-8000-000000000205'::uuid;

DELETE FROM onmaru.identity_members 
WHERE id >= '54500700-0000-4000-8000-000000000056'::uuid 
  AND id <= '54500700-0000-4000-8000-000000000205'::uuid;

SELECT count(*) AS remaining_seeded_reviews_in_range 
FROM onmaru.community_visit_reviews 
WHERE id >= '54500700-0000-4000-9000-000000000501'::uuid 
  AND id <= '54500700-0000-4000-9000-000000002500'::uuid;

COMMIT;

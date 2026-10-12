-- ====================================================================
-- Rollback Warmth Reviews 300 Seed Script
-- Safely removes only the seeded reviews, member profiles, and members
-- ====================================================================
\set ON_ERROR_STOP on
BEGIN;

DELETE FROM onmaru.community_visit_reviews WHERE id::text LIKE '54500700-%';
DELETE FROM onmaru.identity_member_profiles WHERE member_id::text LIKE '54500700-%';
DELETE FROM onmaru.identity_members WHERE id::text LIKE '54500700-%';

SELECT count(*) AS remaining_seeded_reviews FROM onmaru.community_visit_reviews WHERE id::text LIKE '54500700-%';
COMMIT;

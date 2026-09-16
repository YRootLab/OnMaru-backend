\pset tuples_only on
\pset format unaligned
\pset fieldsep '\t'

SELECT 'onmaru.catalog_place_identity', count(*) FROM onmaru.catalog_place_identity
UNION ALL
SELECT 'onmaru.identity_members', count(*) FROM onmaru.identity_members
UNION ALL
SELECT 'onmaru.discovery_explorations', count(*) FROM onmaru.discovery_explorations
UNION ALL
SELECT 'onmaru.discovery_runs', count(*) FROM onmaru.discovery_runs
UNION ALL
SELECT 'onmaru.journey_saved_journeys', count(*) FROM onmaru.journey_saved_journeys
UNION ALL
SELECT 'onmaru.community_visit_reviews', count(*) FROM onmaru.community_visit_reviews
UNION ALL
SELECT 'onmaru.audio_odii_stories', count(*) FROM onmaru.audio_odii_stories
ORDER BY 1;

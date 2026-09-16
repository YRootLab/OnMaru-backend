CREATE TEMP TABLE actual_restore_counts (
  table_name text PRIMARY KEY,
  row_count bigint NOT NULL
);

DO $$
DECLARE
  expected record;
  actual bigint;
  schema_name text;
  relation_name text;
BEGIN
  FOR expected IN SELECT table_name FROM expected_restore_counts LOOP
    schema_name := split_part(expected.table_name, '.', 1);
    relation_name := split_part(expected.table_name, '.', 2);
    EXECUTE format('SELECT count(*) FROM %I.%I', schema_name, relation_name)
      INTO actual;
    INSERT INTO actual_restore_counts (table_name, row_count)
    VALUES (expected.table_name, actual);
  END LOOP;
END
$$;

WITH metrics AS (
  SELECT
    (
      SELECT count(*)
      FROM expected_restore_counts expected
      JOIN actual_restore_counts actual USING (table_name)
      WHERE expected.row_count <> actual.row_count
    ) AS count_mismatches,
    (
      SELECT count(*)
      FROM pg_constraint
      WHERE contype = 'f'
        AND connamespace IN (
          'onmaru'::regnamespace,
          'onmaru_registry'::regnamespace
        )
        AND NOT convalidated
    ) AS unvalidated_foreign_keys,
    (
      SELECT count(*)
      FROM onmaru.journey_saved_journeys
      WHERE snapshot_hash !~ '^[0-9a-f]{64}$'
    ) AS invalid_saved_snapshot_hashes,
    (
      SELECT coalesce(max(version), '000')
      FROM onmaru_registry.migration_version_reservations
    ) AS migration_version,
    (SELECT count(*) FROM restore_deletion_ledger) AS deletion_ledger_entries
), deletion_exposure AS (
  SELECT count(*) AS exposed
  FROM onmaru.identity_members member
  JOIN restore_deletion_ledger replay ON replay.member_id = member.id
  WHERE member.status = 'ACTIVE'
  UNION ALL
  SELECT count(*)
  FROM onmaru.identity_sessions session
  JOIN restore_deletion_ledger replay ON replay.member_id = session.member_id
  WHERE session.revoked_at IS NULL
    AND session.absolute_expires_at > CURRENT_TIMESTAMP
  UNION ALL
  SELECT count(*)
  FROM onmaru.discovery_explorations exploration
  JOIN restore_deletion_ledger replay ON replay.member_id = exploration.owner_member_id
  WHERE exploration.deleted_at IS NULL
  UNION ALL
  SELECT count(*)
  FROM onmaru.discovery_runs run
  JOIN onmaru.discovery_explorations exploration ON exploration.id = run.exploration_id
  JOIN restore_deletion_ledger replay ON replay.member_id = exploration.owner_member_id
  WHERE run.status IN ('QUEUED', 'RUNNING')
  UNION ALL
  SELECT count(*)
  FROM onmaru.community_visit_reviews review
  JOIN restore_deletion_ledger replay ON replay.member_id = review.member_id
  WHERE review.status = 'PUBLISHED'
)
SELECT json_build_object(
  'countMismatches', metrics.count_mismatches,
  'unvalidatedForeignKeys', metrics.unvalidated_foreign_keys,
  'invalidSavedSnapshotHashes', metrics.invalid_saved_snapshot_hashes,
  'migrationVersion', metrics.migration_version,
  'deletionLedgerEntries', metrics.deletion_ledger_entries,
  'deletionReexposureCount', (SELECT sum(exposed) FROM deletion_exposure)
)::text
FROM metrics;

BEGIN;

UPDATE onmaru.identity_members member
SET status = 'DELETING'
FROM restore_deletion_ledger replay
WHERE member.id = replay.member_id;

UPDATE onmaru.identity_sessions session
SET revoked_at = replay.requested_at
FROM restore_deletion_ledger replay
WHERE session.member_id = replay.member_id
  AND session.revoked_at IS NULL;

UPDATE onmaru.discovery_proposals proposal
SET status = 'INVALIDATED'
FROM onmaru.discovery_explorations exploration,
     restore_deletion_ledger replay
WHERE proposal.exploration_id = exploration.id
  AND exploration.owner_member_id = replay.member_id
  AND proposal.status = 'PENDING';

UPDATE onmaru.discovery_runs run
SET status = 'CANCELLED',
    outcome = 'MEMBER_DELETION_RESTORED',
    error_code = 'MEMBER_DELETED'
FROM onmaru.discovery_explorations exploration,
     restore_deletion_ledger replay
WHERE run.exploration_id = exploration.id
  AND exploration.owner_member_id = replay.member_id
  AND run.status IN ('QUEUED', 'RUNNING');

UPDATE onmaru.discovery_explorations exploration
SET deleted_at = replay.requested_at,
    updated_at = GREATEST(exploration.updated_at, replay.requested_at)
FROM restore_deletion_ledger replay
WHERE exploration.owner_member_id = replay.member_id
  AND exploration.deleted_at IS NULL;

UPDATE onmaru.community_visit_reviews review
SET status = 'HIDDEN'
FROM restore_deletion_ledger replay
WHERE review.member_id = replay.member_id
  AND review.status = 'PUBLISHED';

INSERT INTO onmaru.identity_deletion_ledger (
  member_id,
  requested_at,
  completed_at,
  status,
  reason
)
SELECT member_id, requested_at, NULL, 'REQUESTED', 'restore-replay'
FROM restore_deletion_ledger
ON CONFLICT (member_id) DO UPDATE
SET requested_at = LEAST(
      onmaru.identity_deletion_ledger.requested_at,
      EXCLUDED.requested_at
    ),
    status = CASE
      WHEN onmaru.identity_deletion_ledger.status = 'COMPLETED'
        THEN onmaru.identity_deletion_ledger.status
      ELSE 'REQUESTED'::onmaru.identity_deletion_status
    END,
    reason = 'restore-replay';

COMMIT;

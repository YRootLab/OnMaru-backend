package com.yrootlab.onmaru.persistence.operations.retention;

import com.yrootlab.onmaru.operations.retention.RetentionCleanupPolicy;
import com.yrootlab.onmaru.operations.retention.RetentionCleanupResult;
import com.yrootlab.onmaru.operations.retention.RetentionCleanupStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class JdbcRetentionCleanupStore implements RetentionCleanupStore {

    private final DataSource dataSource;

    public JdbcRetentionCleanupStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public RetentionCleanupResult cleanup(RetentionCleanupPolicy policy, Instant now) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var proposal = deleteExpiredProposals(connection, policy, now);
                var run = deleteExpiredRuns(connection, policy, now);
                var saved = deleteSavedResourcesForDeletingMembers(connection, policy, now);
                var stamps = deleteStampResourcesForDeletingMembers(connection, policy, now);
                var receipts = deleteIdempotencyReceiptsForDeletingMembers(connection, policy, now);
                completeMemberDeletionLedgers(connection, now);
                var session = deleteExpiredSessions(connection, policy, now);
                var guest = deleteExpiredGuests(connection, policy, now);
                var revision = deleteInactiveRevisions(connection, policy, now);
                connection.commit();
                return new RetentionCleanupResult(
                        guest.deleted(),
                        session.deleted(),
                        run.deleted(),
                        proposal.deleted(),
                        revision.deleted(),
                        saved.deleted() + stamps.deleted() + receipts.deleted(),
                        session.ledgerEntries()
                                + guest.ledgerEntries()
                                + run.ledgerEntries()
                                + proposal.ledgerEntries()
                                + revision.ledgerEntries()
                                + saved.ledgerEntries()
                                + stamps.ledgerEntries()
                                + receipts.ledgerEntries());
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw databaseFailure((SQLException) exception);
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    private MutationCount deleteExpiredSessions(Connection connection, RetentionCleanupPolicy policy, Instant now)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                WITH doomed AS (
                    SELECT token_hash
                    FROM onmaru.identity_sessions
                    WHERE absolute_expires_at <= ?
                    ORDER BY absolute_expires_at, token_hash
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                ),
                deleted AS (
                    DELETE FROM onmaru.identity_sessions session
                    USING doomed
                    WHERE session.token_hash = doomed.token_hash
                    RETURNING session.token_hash
                ),
                ledger AS (
                    INSERT INTO onmaru.operations_retention_deletion_ledger (
                        id, resource_type, resource_id, reason, deleted_at, details
                    )
                    SELECT gen_random_uuid(), 'IDENTITY_SESSION', token_hash,
                           'SESSION_TTL_EXPIRED', ?, '{}'::jsonb
                    FROM deleted
                    ON CONFLICT (resource_type, resource_id, reason) DO NOTHING
                    RETURNING 1
                )
                SELECT (SELECT COUNT(*) FROM deleted) AS deleted,
                       (SELECT COUNT(*) FROM ledger) AS ledger_entries
                """)) {
            statement.setObject(1, utc(now.minus(policy.sessionTtl())));
            statement.setInt(2, policy.batchSize());
            statement.setObject(3, utc(now));
            return count(statement.executeQuery());
        }
    }

    private MutationCount deleteExpiredGuests(Connection connection, RetentionCleanupPolicy policy, Instant now)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                WITH doomed AS (
                    SELECT id
                    FROM onmaru.identity_guests
                    WHERE expires_at <= ?
                    ORDER BY expires_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                ),
                deleted AS (
                    DELETE FROM onmaru.identity_guests guest
                    USING doomed
                    WHERE guest.id = doomed.id
                    RETURNING guest.id
                ),
                ledger AS (
                    INSERT INTO onmaru.operations_retention_deletion_ledger (
                        id, resource_type, resource_id, reason, deleted_at, details
                    )
                    SELECT gen_random_uuid(), 'IDENTITY_GUEST', id::text,
                           'GUEST_TTL_EXPIRED', ?, '{}'::jsonb
                    FROM deleted
                    ON CONFLICT (resource_type, resource_id, reason) DO NOTHING
                    RETURNING 1
                )
                SELECT (SELECT COUNT(*) FROM deleted) AS deleted,
                       (SELECT COUNT(*) FROM ledger) AS ledger_entries
                """)) {
            statement.setObject(1, utc(now.minus(policy.guestTtl())));
            statement.setInt(2, policy.batchSize());
            statement.setObject(3, utc(now));
            return count(statement.executeQuery());
        }
    }

    private MutationCount deleteExpiredProposals(Connection connection, RetentionCleanupPolicy policy, Instant now)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                WITH doomed AS (
                    SELECT id
                    FROM onmaru.discovery_proposals
                    WHERE expires_at <= ?
                    ORDER BY expires_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                ),
                deleted AS (
                    DELETE FROM onmaru.discovery_proposals proposal
                    USING doomed
                    WHERE proposal.id = doomed.id
                    RETURNING proposal.id
                ),
                ledger AS (
                    INSERT INTO onmaru.operations_retention_deletion_ledger (
                        id, resource_type, resource_id, reason, deleted_at, details
                    )
                    SELECT gen_random_uuid(), 'DISCOVERY_PROPOSAL', id::text,
                           'PROPOSAL_TTL_EXPIRED', ?, '{}'::jsonb
                    FROM deleted
                    ON CONFLICT (resource_type, resource_id, reason) DO NOTHING
                    RETURNING 1
                )
                SELECT (SELECT COUNT(*) FROM deleted) AS deleted,
                       (SELECT COUNT(*) FROM ledger) AS ledger_entries
                """)) {
            statement.setObject(1, utc(now.minus(policy.proposalTtl())));
            statement.setInt(2, policy.batchSize());
            statement.setObject(3, utc(now));
            return count(statement.executeQuery());
        }
    }

    private MutationCount deleteExpiredRuns(Connection connection, RetentionCleanupPolicy policy, Instant now)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                WITH doomed AS (
                    SELECT id
                    FROM onmaru.discovery_runs
                    WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                      AND deadline_at <= ?
                    ORDER BY deadline_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                ),
                deleted AS (
                    DELETE FROM onmaru.discovery_runs run
                    USING doomed
                    WHERE run.id = doomed.id
                    RETURNING run.id
                ),
                ledger AS (
                    INSERT INTO onmaru.operations_retention_deletion_ledger (
                        id, resource_type, resource_id, reason, deleted_at, details
                    )
                    SELECT gen_random_uuid(), 'DISCOVERY_RUN', id::text,
                           'RUN_TTL_EXPIRED', ?, '{}'::jsonb
                    FROM deleted
                    ON CONFLICT (resource_type, resource_id, reason) DO NOTHING
                    RETURNING 1
                )
                SELECT (SELECT COUNT(*) FROM deleted) AS deleted,
                       (SELECT COUNT(*) FROM ledger) AS ledger_entries
                """)) {
            statement.setObject(1, utc(now.minus(policy.runTtl())));
            statement.setInt(2, policy.batchSize());
            statement.setObject(3, utc(now));
            return count(statement.executeQuery());
        }
    }

    private MutationCount deleteInactiveRevisions(Connection connection, RetentionCleanupPolicy policy, Instant now)
            throws SQLException {
        var revisionIds = findRevisionsToDelete(connection, policy, now);
        int deleted = 0;
        int ledgerEntries = 0;
        for (var revisionId : revisionIds) {
            detachRevisionReferences(connection, revisionId);
            deleteRevisionChildren(connection, revisionId);
            deleted += deleteRevision(connection, revisionId);
            ledgerEntries += recordRevisionDeletion(connection, revisionId, now);
        }
        return new MutationCount(deleted, ledgerEntries);
    }

    private List<UUID> findRevisionsToDelete(
            Connection connection,
            RetentionCleanupPolicy policy,
            Instant now
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                WITH protected_revisions AS (
                    SELECT active.revision_id AS id
                    FROM onmaru.catalog_active_datasets active
                    UNION
                    SELECT previous.id
                    FROM onmaru.catalog_active_datasets active
                    JOIN LATERAL (
                        SELECT revision.id
                        FROM onmaru.catalog_dataset_revisions revision
                        WHERE revision.dataset = active.dataset
                          AND revision.status = 'PUBLISHED'
                          AND revision.id <> active.revision_id
                        ORDER BY revision.published_at DESC, revision.fetched_at DESC, revision.id
                        LIMIT 1
                    ) previous ON true
                )
                SELECT revision.id
                FROM onmaru.catalog_dataset_revisions revision
                WHERE revision.id NOT IN (SELECT id FROM protected_revisions)
                  AND (
                      revision.status = 'PUBLISHED'
                      OR (
                          revision.status <> 'PUBLISHED'
                          AND revision.fetched_at <= ?
                      )
                  )
                ORDER BY revision.fetched_at, revision.id
                LIMIT ?
                FOR UPDATE OF revision SKIP LOCKED
                """)) {
            statement.setObject(1, utc(now.minus(policy.inactiveRevisionTtl())));
            statement.setInt(2, policy.batchSize());
            var revisionIds = new ArrayList<UUID>();
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    revisionIds.add(result.getObject("id", UUID.class));
                }
            }
            return List.copyOf(revisionIds);
        }
    }

    private void detachRevisionReferences(Connection connection, UUID revisionId) throws SQLException {
        executeRevisionMutation(connection, """
                UPDATE onmaru.operations_sync_runs
                SET revision_id = NULL
                WHERE revision_id = ?
                """, revisionId);
        executeRevisionMutation(connection, """
                UPDATE onmaru.catalog_dataset_revisions
                SET base_revision_id = NULL
                WHERE base_revision_id = ?
                """, revisionId);
    }

    private void deleteRevisionChildren(Connection connection, UUID revisionId) throws SQLException {
        for (var table : REVISION_CHILD_TABLES_IN_DELETE_ORDER) {
            executeRevisionMutation(connection,
                    "DELETE FROM onmaru." + table + " WHERE revision_id = ?", revisionId);
        }
    }

    private int deleteRevision(Connection connection, UUID revisionId) throws SQLException {
        return executeRevisionMutation(connection, """
                DELETE FROM onmaru.catalog_dataset_revisions
                WHERE id = ?
                """, revisionId);
    }

    private int recordRevisionDeletion(Connection connection, UUID revisionId, Instant now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.operations_retention_deletion_ledger (
                    id, resource_type, resource_id, reason, deleted_at, details
                ) VALUES (gen_random_uuid(), 'CATALOG_REVISION', ?,
                          'INACTIVE_REVISION_GC', ?, '{"publishedCopiesRetained": 2}'::jsonb)
                ON CONFLICT (resource_type, resource_id, reason) DO NOTHING
                """)) {
            statement.setString(1, revisionId.toString());
            statement.setObject(2, utc(now));
            return statement.executeUpdate();
        }
    }

    private int executeRevisionMutation(Connection connection, String sql, UUID revisionId) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, revisionId);
            return statement.executeUpdate();
        }
    }

    private static final List<String> REVISION_CHILD_TABLES_IN_DELETE_ORDER = List.of(
            "audio_story_content_tag_versions",
            "audio_subtitle_lines",
            "audio_story_versions",
            "audio_spot_versions",
            "catalog_place_content_tag_versions",
            "catalog_hanok_detail_versions",
            "catalog_place_image_versions",
            "catalog_kto_korean_info_versions",
            "catalog_kto_korean_intro_versions",
            "catalog_kto_korean_content_versions",
            "catalog_place_versions",
            "insights_concentration_observations",
            "insights_visitor_observations",
            "operations_sync_watermarks",
            "audio_revision_stages"
    );

    private MutationCount deleteSavedResourcesForDeletingMembers(
            Connection connection,
            RetentionCleanupPolicy policy,
            Instant now
    ) throws SQLException {
        var resources = deleteSavedResources(connection, policy, now);
        var journeys = deleteSavedJourneys(connection, policy, now);
        return new MutationCount(
                resources.deleted() + journeys.deleted(),
                resources.ledgerEntries() + journeys.ledgerEntries());
    }

    private MutationCount deleteStampResourcesForDeletingMembers(
            Connection connection,
            RetentionCleanupPolicy policy,
            Instant now
    ) throws SQLException {
        var awards = deleteMemberResource(
                connection, policy, now, "stamp_awards", "STAMP_AWARD", "awarded_at", "TRUE");
        var checkIns = deleteMemberResource(
                connection, policy, now, "stamp_check_ins", "STAMP_CHECK_IN", "checked_in_at", """
                        NOT EXISTS (
                            SELECT 1 FROM onmaru.stamp_awards award
                            WHERE award.member_id = resource.member_id
                              AND award.trigger_check_in_id = resource.id
                        )
                        """);
        var profiles = deleteMemberResource(
                connection, policy, now, "stamp_ranking_profiles", "STAMP_RANKING_PROFILE", "updated_at", "TRUE");
        return new MutationCount(
                awards.deleted() + checkIns.deleted() + profiles.deleted(),
                awards.ledgerEntries() + checkIns.ledgerEntries() + profiles.ledgerEntries());
    }

    private MutationCount deleteIdempotencyReceiptsForDeletingMembers(
            Connection connection,
            RetentionCleanupPolicy policy,
            Instant now
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                WITH doomed AS (
                    SELECT receipt.subject_id, receipt.idempotency_key, receipt.method, receipt.path
                    FROM onmaru.web_idempotency_receipts receipt
                    JOIN onmaru.identity_deletion_ledger member_deletion
                      ON receipt.subject_id = member_deletion.member_id::text
                    JOIN onmaru.identity_members member ON member.id = member_deletion.member_id
                    WHERE member_deletion.status = 'REQUESTED' AND member.status = 'DELETING'
                    ORDER BY receipt.created_at, receipt.subject_id, receipt.idempotency_key,
                             receipt.method, receipt.path
                    LIMIT ?
                    FOR UPDATE OF receipt, member SKIP LOCKED
                ),
                deleted AS (
                    DELETE FROM onmaru.web_idempotency_receipts receipt
                    USING doomed
                    WHERE receipt.subject_id = doomed.subject_id
                      AND receipt.idempotency_key = doomed.idempotency_key
                      AND receipt.method = doomed.method
                      AND receipt.path = doomed.path
                    RETURNING receipt.subject_id, receipt.idempotency_key, receipt.method, receipt.path
                ),
                ledger AS (
                    INSERT INTO onmaru.operations_retention_deletion_ledger (
                        id, resource_type, resource_id, reason, deleted_at, details
                    )
                    SELECT gen_random_uuid(), 'WEB_IDEMPOTENCY_RECEIPT',
                           encode(digest(
                               concat_ws(E'\\x1f', subject_id, idempotency_key::text, method, path),
                               'sha256'), 'hex'),
                           'MEMBER_DELETION_REQUESTED', ?, '{}'::jsonb
                    FROM deleted
                    ON CONFLICT (resource_type, resource_id, reason) DO NOTHING
                    RETURNING 1
                )
                SELECT (SELECT COUNT(*) FROM deleted) AS deleted,
                       (SELECT COUNT(*) FROM ledger) AS ledger_entries
                """)) {
            statement.setInt(1, policy.batchSize());
            statement.setObject(2, utc(now));
            return count(statement.executeQuery());
        }
    }

    private MutationCount deleteMemberResource(
            Connection connection,
            RetentionCleanupPolicy policy,
            Instant now,
            String table,
            String resourceType,
            String orderColumn,
            String eligibility
    ) throws SQLException {
        var sql = """
                WITH doomed AS (
                    SELECT resource.%s
                    FROM onmaru.%s resource
                    JOIN onmaru.identity_deletion_ledger member_deletion
                      ON member_deletion.member_id = resource.member_id
                    JOIN onmaru.identity_members member ON member.id = resource.member_id
                    WHERE member_deletion.status = 'REQUESTED' AND member.status = 'DELETING'
                      AND (%s)
                    ORDER BY resource.%s, resource.%s
                    LIMIT ?
                    FOR UPDATE OF resource, member SKIP LOCKED
                ),
                deleted AS (
                    DELETE FROM onmaru.%s resource
                    USING doomed
                    WHERE resource.%s = doomed.%s
                    RETURNING resource.%s
                ),
                ledger AS (
                    INSERT INTO onmaru.operations_retention_deletion_ledger (
                        id, resource_type, resource_id, reason, deleted_at, details
                    )
                    SELECT gen_random_uuid(), ?, %s::text,
                           'MEMBER_DELETION_REQUESTED', ?, '{}'::jsonb
                    FROM deleted
                    ON CONFLICT (resource_type, resource_id, reason) DO NOTHING
                    RETURNING 1
                )
                SELECT (SELECT COUNT(*) FROM deleted) AS deleted,
                       (SELECT COUNT(*) FROM ledger) AS ledger_entries
                """.formatted(
                table.equals("stamp_ranking_profiles") ? "member_id" : "id",
                table,
                eligibility,
                orderColumn,
                table.equals("stamp_ranking_profiles") ? "member_id" : "id",
                table,
                table.equals("stamp_ranking_profiles") ? "member_id" : "id",
                table.equals("stamp_ranking_profiles") ? "member_id" : "id",
                table.equals("stamp_ranking_profiles") ? "member_id" : "id",
                table.equals("stamp_ranking_profiles") ? "member_id" : "id");
        try (var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, policy.batchSize());
            statement.setString(2, resourceType);
            statement.setObject(3, utc(now));
            return count(statement.executeQuery());
        }
    }

    private MutationCount deleteSavedResources(Connection connection, RetentionCleanupPolicy policy, Instant now)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                WITH doomed AS (
                    SELECT saved.id
                    FROM onmaru.journey_saved_resources saved
                    JOIN onmaru.identity_deletion_ledger ledger
                      ON ledger.member_id = saved.member_id
                    WHERE ledger.status = 'REQUESTED'
                    ORDER BY saved.saved_at, saved.id
                    LIMIT ?
                    FOR UPDATE OF saved SKIP LOCKED
                ),
                deleted AS (
                    DELETE FROM onmaru.journey_saved_resources saved
                    USING doomed
                    WHERE saved.id = doomed.id
                    RETURNING saved.id
                ),
                ledger AS (
                    INSERT INTO onmaru.operations_retention_deletion_ledger (
                        id, resource_type, resource_id, reason, deleted_at, details
                    )
                    SELECT gen_random_uuid(), 'JOURNEY_SAVED_RESOURCE', id::text,
                           'MEMBER_DELETION_REQUESTED', ?, '{}'::jsonb
                    FROM deleted
                    ON CONFLICT (resource_type, resource_id, reason) DO NOTHING
                    RETURNING 1
                )
                SELECT (SELECT COUNT(*) FROM deleted) AS deleted,
                       (SELECT COUNT(*) FROM ledger) AS ledger_entries
                """)) {
            statement.setInt(1, policy.batchSize());
            statement.setObject(2, utc(now));
            return count(statement.executeQuery());
        }
    }

    private MutationCount deleteSavedJourneys(Connection connection, RetentionCleanupPolicy policy, Instant now)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                WITH doomed AS (
                    SELECT saved.id
                    FROM onmaru.journey_saved_journeys saved
                    JOIN onmaru.identity_deletion_ledger ledger
                      ON ledger.member_id = saved.member_id
                    WHERE ledger.status = 'REQUESTED'
                    ORDER BY saved.saved_at, saved.id
                    LIMIT ?
                    FOR UPDATE OF saved SKIP LOCKED
                ),
                deleted AS (
                    DELETE FROM onmaru.journey_saved_journeys saved
                    USING doomed
                    WHERE saved.id = doomed.id
                    RETURNING saved.id
                ),
                ledger AS (
                    INSERT INTO onmaru.operations_retention_deletion_ledger (
                        id, resource_type, resource_id, reason, deleted_at, details
                    )
                    SELECT gen_random_uuid(), 'JOURNEY_SAVED_JOURNEY', id::text,
                           'MEMBER_DELETION_REQUESTED', ?, '{}'::jsonb
                    FROM deleted
                    ON CONFLICT (resource_type, resource_id, reason) DO NOTHING
                    RETURNING 1
                )
                SELECT (SELECT COUNT(*) FROM deleted) AS deleted,
                       (SELECT COUNT(*) FROM ledger) AS ledger_entries
                """)) {
            statement.setInt(1, policy.batchSize());
            statement.setObject(2, utc(now));
            return count(statement.executeQuery());
        }
    }

    private void completeMemberDeletionLedgers(Connection connection, Instant now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE onmaru.identity_deletion_ledger ledger
                SET status = 'COMPLETED', completed_at = ?
                WHERE ledger.status = 'REQUESTED'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM onmaru.journey_saved_resources saved
                      WHERE saved.member_id = ledger.member_id
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM onmaru.journey_saved_journeys saved
                      WHERE saved.member_id = ledger.member_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM onmaru.stamp_check_ins stamp
                      WHERE stamp.member_id = ledger.member_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM onmaru.stamp_awards award
                      WHERE award.member_id = ledger.member_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM onmaru.stamp_ranking_profiles profile
                      WHERE profile.member_id = ledger.member_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM onmaru.web_idempotency_receipts receipt
                      WHERE receipt.subject_id = ledger.member_id::text
                  )
                """)) {
            statement.setObject(1, utc(now));
            statement.executeUpdate();
        }
    }

    private MutationCount count(ResultSet result) throws SQLException {
        try (result) {
            if (!result.next()) {
                return new MutationCount(0, 0);
            }
            return new MutationCount(result.getInt("deleted"), result.getInt("ledger_entries"));
        }
    }

    private OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private IllegalStateException databaseFailure(SQLException exception) {
        return new IllegalStateException("retention cleanup persistence failed", exception);
    }

    private record MutationCount(int deleted, int ledgerEntries) {
    }
}

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
                        saved.deleted(),
                        session.ledgerEntries()
                                + guest.ledgerEntries()
                                + run.ledgerEntries()
                                + proposal.ledgerEntries()
                                + revision.ledgerEntries()
                                + saved.ledgerEntries());
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
        try (var statement = connection.prepareStatement("""
                WITH doomed AS (
                    SELECT revision.id
                    FROM onmaru.catalog_dataset_revisions revision
                    WHERE revision.status <> 'PUBLISHED'
                      AND revision.fetched_at <= ?
                      AND NOT EXISTS (
                          SELECT 1
                          FROM onmaru.catalog_active_datasets active
                          WHERE active.revision_id = revision.id
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM onmaru.catalog_dataset_revisions child
                          WHERE child.base_revision_id = revision.id
                      )
                    ORDER BY revision.fetched_at, revision.id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                ),
                deleted AS (
                    DELETE FROM onmaru.catalog_dataset_revisions revision
                    USING doomed
                    WHERE revision.id = doomed.id
                    RETURNING revision.id
                ),
                ledger AS (
                    INSERT INTO onmaru.operations_retention_deletion_ledger (
                        id, resource_type, resource_id, reason, deleted_at, details
                    )
                    SELECT gen_random_uuid(), 'CATALOG_REVISION', id::text,
                           'INACTIVE_REVISION_GC', ?, '{}'::jsonb
                    FROM deleted
                    ON CONFLICT (resource_type, resource_id, reason) DO NOTHING
                    RETURNING 1
                )
                SELECT (SELECT COUNT(*) FROM deleted) AS deleted,
                       (SELECT COUNT(*) FROM ledger) AS ledger_entries
                """)) {
            statement.setObject(1, utc(now.minus(policy.inactiveRevisionTtl())));
            statement.setInt(2, policy.batchSize());
            statement.setObject(3, utc(now));
            return count(statement.executeQuery());
        }
    }

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

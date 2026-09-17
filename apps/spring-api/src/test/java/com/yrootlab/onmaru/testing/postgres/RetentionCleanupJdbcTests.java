package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.operations.retention.RetentionCleanupPolicy;
import com.yrootlab.onmaru.persistence.operations.retention.JdbcRetentionCleanupStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionCleanupJdbcTests {

    private static final int POSTGRES_PORT = 5432;
    private static final String DATABASE = "onmaru_test";
    private static final String USERNAME = "onmaru_test";
    private static final String PASSWORD = "onmaru_test";
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    private static final GenericContainer<?> postgres = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(POSTGRES_PORT)
            .withEnv("POSTGRES_DB", DATABASE)
            .withEnv("POSTGRES_USER", USERNAME)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @BeforeAll
    static void startPostgres() {
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @Test
    void cleanupIsBatchBoundedAndRestartSafeAtTtlBoundary() throws Exception {
        resetAndMigrate();
        var memberId = UUID.randomUUID();
        var expiredGuestId = UUID.randomUUID();
        var freshGuestId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var proposalId = UUID.randomUUID();
        var inactiveRevisionId = UUID.randomUUID();
        var activeRevisionId = UUID.randomUUID();
        var savedResourceId = UUID.randomUUID();
        seedCleanupFixtures(
                memberId,
                expiredGuestId,
                freshGuestId,
                explorationId,
                runId,
                proposalId,
                inactiveRevisionId,
                activeRevisionId,
                savedResourceId);
        var store = new JdbcRetentionCleanupStore(dataSource());
        var policy = new RetentionCleanupPolicy(
                1,
                Duration.ofDays(30),
                Duration.ofDays(1),
                Duration.ofHours(1),
                Duration.ofDays(7),
                Duration.ofDays(14));

        var first = store.cleanup(policy, NOW);
        var replay = store.cleanup(policy, NOW);

        assertThat(first.expiredGuests()).isEqualTo(1);
        assertThat(first.expiredSessions()).isEqualTo(1);
        assertThat(first.expiredRuns()).isEqualTo(1);
        assertThat(first.expiredProposals()).isEqualTo(1);
        assertThat(first.inactiveRevisions()).isEqualTo(1);
        assertThat(first.memberDeletionResources()).isEqualTo(2);
        assertThat(replay.ledgerEntries()).isZero();
        assertRemainingRows(activeRevisionId, freshGuestId);
    }

    private static void seedCleanupFixtures(
            UUID memberId,
            UUID expiredGuestId,
            UUID freshGuestId,
            UUID explorationId,
            UUID runId,
            UUID proposalId,
            UUID inactiveRevisionId,
            UUID activeRevisionId,
            UUID savedResourceId
    ) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.identity_members (id, status, created_at)
                    VALUES ('%s', 'ACTIVE', '2026-09-01T00:00:00Z')
                    """.formatted(memberId));
            statement.execute("""
                    INSERT INTO onmaru.identity_sessions (
                        token_hash, member_id, created_at, last_seen_at, absolute_expires_at, revoked_at
                    ) VALUES
                        ('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '%s',
                         '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z', '2026-09-15T23:59:59Z', NULL),
                        ('bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', '%s',
                         '2026-09-17T00:00:00Z', '2026-09-17T00:00:00Z', '2026-09-16T12:00:00Z', NULL)
                    """.formatted(memberId, memberId));
            statement.execute("""
                    INSERT INTO onmaru.identity_guests (id, token_hash, expires_at)
                    VALUES
                        ('%s', 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                         '2026-08-17T00:00:00Z'),
                        ('%s', 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                         '2026-08-18T00:00:01Z')
                    """.formatted(expiredGuestId, freshGuestId));
            statement.execute("""
                    INSERT INTO onmaru.discovery_explorations (
                        id, owner_member_id, state_version, pinned_refs, excluded_refs, created_at, updated_at
                    ) VALUES (
                        '%s', '%s', 0, '[]'::jsonb, '[]'::jsonb,
                        '2026-09-16T00:00:00Z', '2026-09-16T00:00:00Z'
                    )
                    """.formatted(explorationId, memberId));
            statement.execute("""
                    INSERT INTO onmaru.discovery_runs (
                        id, exploration_id, actor_key, base_version, status, stage, outcome,
                        created_at, deadline_at, started_at, generation, error_code, engine
                    ) VALUES (
                        '%s', '%s', 'member:%s', 0, 'FAILED', NULL, NULL,
                        '2026-09-16T00:00:00Z', '2026-09-16T22:59:59Z',
                        '2026-09-16T00:00:01Z', 1, 'RUN_DEADLINE_EXCEEDED', 'baseline'
                    )
                    """.formatted(runId, explorationId, memberId));
            statement.execute("""
                    INSERT INTO onmaru.discovery_proposals (
                        id, run_id, exploration_id, base_version, ordered_refs, reasons,
                        evidence, expires_at, status
                    ) VALUES (
                        '%s', '%s', '%s', 0, '[]'::jsonb, '{}'::jsonb,
                        '{}'::jsonb, '2026-09-09T23:59:59Z', 'INVALIDATED'
                    )
                    """.formatted(proposalId, runId, explorationId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_dataset_revisions (
                        id, dataset, status, fetched_at, published_at
                    ) VALUES
                        ('%s', 'odii', 'STAGING', '2026-09-01T00:00:00Z', NULL),
                        ('%s', 'odii', 'PUBLISHED', '2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z')
                    """.formatted(inactiveRevisionId, activeRevisionId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                    VALUES ('odii', '%s', '2026-09-02T00:00:00Z')
                    """.formatted(activeRevisionId));
            statement.execute("""
                    INSERT INTO onmaru.journey_saved_resources (
                        id, member_id, resource_type, resource_id, saved_at
                    ) VALUES (
                        gen_random_uuid(), '%s', 'PLACE', '%s', '2026-09-16T00:00:00Z'
                    )
                    """.formatted(memberId, savedResourceId));
            statement.execute("""
                    INSERT INTO onmaru.journey_saved_journeys (
                        id, member_id, source_exploration_id, source_version, saved_at,
                        title, snapshot, snapshot_hash
                    ) VALUES (
                        gen_random_uuid(), '%s', '%s', 0, '2026-09-16T00:00:00Z',
                        'saved', '{}'::jsonb, 'hash'
                    )
                    """.formatted(memberId, explorationId));
            statement.execute("UPDATE onmaru.identity_members SET status = 'DELETING' WHERE id = '%s'"
                    .formatted(memberId));
            statement.execute("""
                    INSERT INTO onmaru.identity_deletion_ledger (member_id, requested_at, status, reason)
                    VALUES ('%s', '2026-09-16T00:00:00Z', 'REQUESTED', 'USER_REQUESTED')
                    """.formatted(memberId));
        }
    }

    private static void assertRemainingRows(UUID activeRevisionId, UUID freshGuestId) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.identity_sessions"))
                    .isEqualTo(1);
            assertThat(countRows(statement, """
                    SELECT COUNT(*) FROM onmaru.identity_guests WHERE id = '%s'
                    """.formatted(freshGuestId))).isEqualTo(1);
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.discovery_runs"))
                    .isZero();
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.discovery_proposals"))
                    .isZero();
            assertThat(countRows(statement, """
                    SELECT COUNT(*) FROM onmaru.catalog_dataset_revisions WHERE id = '%s'
                    """.formatted(activeRevisionId))).isEqualTo(1);
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.journey_saved_resources"))
                    .isZero();
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.journey_saved_journeys"))
                    .isZero();
            assertThat(countRows(statement, """
                    SELECT COUNT(*) FROM onmaru.identity_deletion_ledger WHERE status = 'COMPLETED'
                    """)).isEqualTo(1);
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.operations_retention_deletion_ledger"))
                    .isEqualTo(7);
        }
    }

    private static void resetAndMigrate() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD)) {
            PostgresTestDatabase.reset(connection);
        }
        Flyway.configure()
                .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                .locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private static DataSource dataSource() {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return DriverManager.getConnection(jdbcUrl(), username, password);
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLFeatureNotSupportedException();
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    private static int countRows(java.sql.Statement statement, String sql) throws Exception {
        try (var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(),
                postgres.getMappedPort(POSTGRES_PORT),
                DATABASE);
    }
}

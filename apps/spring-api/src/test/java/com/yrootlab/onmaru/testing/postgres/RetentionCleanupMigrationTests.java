package com.yrootlab.onmaru.testing.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetentionCleanupMigrationTests {

    private static final int POSTGRES_PORT = 5432;
    private static final String DATABASE = "onmaru_test";
    private static final String USERNAME = "onmaru_test";
    private static final String PASSWORD = "onmaru_test";

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
    void migratesRetentionCleanupLedgerSchema() throws Exception {
        resetAndMigrate();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'onmaru'
                      AND table_name = 'operations_retention_deletion_ledger'
                    """)).isEqualTo(1);
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM pg_indexes
                    WHERE schemaname = 'onmaru'
                      AND indexname IN (
                        'operations_retention_deletion_ledger_resource_uq',
                        'identity_sessions_retention_expired_idx',
                        'identity_guests_retention_expired_idx',
                        'discovery_runs_retention_terminal_idx',
                        'discovery_proposals_retention_expired_idx',
                        'catalog_dataset_revisions_retention_inactive_idx'
                      )
                    """)).isEqualTo(6);
        }
    }

    @Test
    void deletionLedgerIsReplaySafeForSameResourceReason() throws Exception {
        resetAndMigrate();
        var resourceId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.operations_retention_deletion_ledger (
                        id, resource_type, resource_id, reason, deleted_at, details
                    ) VALUES (
                        gen_random_uuid(), 'IDENTITY_SESSION', '%s', 'SESSION_TTL_EXPIRED',
                        '2026-09-17T00:00:00Z', '{"batch":"first"}'::jsonb
                    )
                    """.formatted(resourceId));

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO onmaru.operations_retention_deletion_ledger (
                        id, resource_type, resource_id, reason, deleted_at, details
                    ) VALUES (
                        gen_random_uuid(), 'IDENTITY_SESSION', '%s', 'SESSION_TTL_EXPIRED',
                        '2026-09-17T00:00:01Z', '{"batch":"replay"}'::jsonb
                    )
                    """.formatted(resourceId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("operations_retention_deletion_ledger_resource_uq");
        }
    }

    @Test
    void deletionLedgerBlocksLateSavedResourceWritesForDeletingMember() throws Exception {
        resetAndMigrate();
        var memberId = UUID.randomUUID();
        var resourceId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.identity_members (id, status, created_at)
                    VALUES ('%s', 'DELETING', '2026-09-17T00:00:00Z')
                    """.formatted(memberId));
            statement.execute("""
                    INSERT INTO onmaru.identity_deletion_ledger (member_id, requested_at, status, reason)
                    VALUES ('%s', '2026-09-17T00:00:00Z', 'REQUESTED', 'USER_REQUESTED')
                    """.formatted(memberId));

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO onmaru.journey_saved_resources (
                        id, member_id, resource_type, resource_id, saved_at
                    ) VALUES (
                        gen_random_uuid(), '%s', 'PLACE', '%s', '2026-09-17T00:00:01Z'
                    )
                    """.formatted(memberId, resourceId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("member deletion ledger blocks saved resource writes");
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

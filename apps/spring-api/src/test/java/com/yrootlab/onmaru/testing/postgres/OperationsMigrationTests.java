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
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationsMigrationTests {

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
    void migratesSyncOperationsAndOutboxSchema() throws Exception {
        resetAndMigrate();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'onmaru'
                      AND table_name IN (
                        'operations_admission',
                        'operations_sync_schedules',
                        'operations_sync_runs',
                        'operations_sync_checkpoints',
                        'operations_sync_leases',
                        'operations_sync_watermarks',
                        'operations_sync_quarantine',
                        'operations_outbox_events'
                      )
                    """)).isEqualTo(8);
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM pg_index i
                    JOIN pg_class c ON c.oid = i.indexrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'onmaru'
                      AND c.relname = 'operations_outbox_events_undelivered_idx'
                      AND pg_get_expr(i.indpred, i.indrelid) ILIKE '%delivered_at IS NULL%'
                    """)).isEqualTo(1);
        }
    }

    @Test
    void preventsDuplicateScheduleAttemptsAndRequiresRevisionForWatermark() throws Exception {
        resetAndMigrate();
        var revisionId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertRevision(statement, revisionId, "kto-korean-tour");
            insertRun(statement, UUID.randomUUID(), "kto-korean-tour", 1, revisionId);

            assertThatThrownBy(() -> insertRun(statement, UUID.randomUUID(), "kto-korean-tour", 1, revisionId))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("operations_sync_runs_dataset_scheduled_for_attempt_uq");

            statement.execute("""
                    INSERT INTO onmaru.operations_sync_watermarks (
                        dataset, source_modified_at, external_id, last_full_success_at,
                        last_success_at, revision_id
                    ) VALUES (
                        'kto-korean-tour', '20260915030000', '100',
                        '2026-09-15T03:05:00+09:00',
                        '2026-09-15T03:05:00+09:00',
                        '%s'
                    )
                    """.formatted(revisionId));

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO onmaru.operations_sync_watermarks (
                        dataset, revision_id
                    ) VALUES (
                        'missing-revision', gen_random_uuid()
                    )
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("operations_sync_watermarks_revision_id_fkey");
        }
    }

    @Test
    void staleLeaseOwnerCannotFenceWriteAfterGenerationChanges() throws Exception {
        resetAndMigrate();
        var runId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertRun(statement, runId, "kto-korean-tour", 1, null);
            statement.execute("""
                    INSERT INTO onmaru.operations_sync_leases (
                        dataset, owner_token, generation, lease_until
                    ) VALUES (
                        'kto-korean-tour', 'worker-a', 1, CURRENT_TIMESTAMP + interval '30 seconds'
                    )
                    """);

            statement.execute("""
                    UPDATE onmaru.operations_sync_leases
                    SET owner_token = 'worker-b',
                        generation = generation + 1,
                        lease_until = CURRENT_TIMESTAMP + interval '30 seconds'
                    WHERE dataset = 'kto-korean-tour'
                      AND owner_token = 'worker-a'
                      AND generation = 1
                      AND lease_until > CURRENT_TIMESTAMP
                    """);

            var staleWriteCount = statement.executeUpdate("""
                    INSERT INTO onmaru.operations_sync_checkpoints (
                        run_id, partition_key, next_page, seen_count
                    )
                    SELECT '%s', 'default', 2, 100
                    WHERE EXISTS (
                        SELECT 1
                        FROM onmaru.operations_sync_leases
                        WHERE dataset = 'kto-korean-tour'
                          AND owner_token = 'worker-a'
                          AND generation = 1
                          AND lease_until > CURRENT_TIMESTAMP
                    )
                    """.formatted(runId));

            assertThat(staleWriteCount).isZero();
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.operations_sync_checkpoints"))
                    .isZero();
        }
    }

    @Test
    void checkpointAndStagedRevisionAreAtomicInOneTransaction() throws Exception {
        resetAndMigrate();
        var rollbackRevisionId = UUID.randomUUID();
        var rollbackRunId = UUID.randomUUID();
        var committedRevisionId = UUID.randomUUID();
        var committedRunId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD)) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                insertRevision(statement, rollbackRevisionId, "rollback-dataset");
                insertRun(statement, rollbackRunId, "rollback-dataset", 1, rollbackRevisionId);
                insertCheckpoint(statement, rollbackRunId, "default", 2, 100);
                connection.rollback();
            }

            try (var statement = connection.createStatement()) {
                assertThat(countRows(statement, """
                        SELECT COUNT(*)
                        FROM onmaru.catalog_dataset_revisions
                        WHERE id = '%s'
                        """.formatted(rollbackRevisionId))).isZero();
                assertThat(countRows(statement, """
                        SELECT COUNT(*)
                        FROM onmaru.operations_sync_checkpoints
                        WHERE run_id = '%s'
                        """.formatted(rollbackRunId))).isZero();
            }

            try (var statement = connection.createStatement()) {
                insertRevision(statement, committedRevisionId, "committed-dataset");
                insertRun(statement, committedRunId, "committed-dataset", 1, committedRevisionId);
                insertCheckpoint(statement, committedRunId, "default", 3, 200);
                connection.commit();
            }

            try (var statement = connection.createStatement()) {
                assertThat(countRows(statement, """
                        SELECT COUNT(*)
                        FROM onmaru.catalog_dataset_revisions
                        WHERE id = '%s'
                        """.formatted(committedRevisionId))).isEqualTo(1);
                assertThat(countRows(statement, """
                        SELECT COUNT(*)
                        FROM onmaru.operations_sync_checkpoints
                        WHERE run_id = '%s'
                        """.formatted(committedRunId))).isEqualTo(1);
            }
        }
    }

    private static void insertRevision(Statement statement, UUID revisionId, String dataset) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.catalog_dataset_revisions (
                    id, dataset, status, source_observed_at, fetched_at
                ) VALUES (
                    '%s', '%s', 'STAGING', '2026-09-15T03:00:00+09:00',
                    '2026-09-15T03:01:00+09:00'
                )
                """.formatted(revisionId, dataset));
    }

    private static void insertRun(
            Statement statement,
            UUID runId,
            String dataset,
            int attempt,
            UUID revisionId
    ) throws Exception {
        var revisionValue = revisionId == null ? "NULL" : "'%s'".formatted(revisionId);
        statement.execute("""
                INSERT INTO onmaru.operations_sync_runs (
                    id, dataset, scheduled_for, attempt, status, revision_id,
                    started_at, counts
                ) VALUES (
                    '%s', '%s', '2026-09-15T03:00:00+09:00', %d, 'RUNNING', %s,
                    '2026-09-15T03:00:01+09:00', '{"seen":0}'::jsonb
                )
                """.formatted(runId, dataset, attempt, revisionValue));
    }

    private static void insertCheckpoint(
            Statement statement,
            UUID runId,
            String partitionKey,
            int nextPage,
            int seenCount
    ) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.operations_sync_checkpoints (
                    run_id, partition_key, next_page, seen_count
                ) VALUES (
                    '%s', '%s', %d, %d
                )
                """.formatted(runId, partitionKey, nextPage, seenCount));
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

    private static int countRows(Statement statement, String sql) throws Exception {
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

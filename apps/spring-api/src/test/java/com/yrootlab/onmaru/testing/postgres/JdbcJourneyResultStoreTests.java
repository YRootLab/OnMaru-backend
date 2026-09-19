package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.journey.worker.JourneyResultEngine;
import com.yrootlab.onmaru.journey.worker.PersistJourneyResultCommand;
import com.yrootlab.onmaru.journey.worker.PersistJourneyResultResult;
import com.yrootlab.onmaru.journey.worker.WorkerDegradedReason;
import com.yrootlab.onmaru.persistence.journey.worker.JdbcJourneyResultStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcJourneyResultStoreTests {

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

    private DataSource dataSource;
    private JdbcJourneyResultStore store;
    private UUID memberId;
    private UUID explorationId;
    private UUID runId;

    @BeforeAll
    static void startPostgres() {
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @BeforeEach
    void resetDatabase() throws Exception {
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
        dataSource = new DriverManagerDataSource(jdbcUrl(), USERNAME, PASSWORD);
        store = new JdbcJourneyResultStore(dataSource);
        memberId = UUID.randomUUID();
        explorationId = UUID.randomUUID();
        runId = UUID.randomUUID();
        insertFixture("RUNNING", "30 seconds");
    }

    @Test
    void persistsBaselineBoardOnlyForActiveRunningRunAndMatchingBaseVersion() {
        var result = store.persist(new PersistJourneyResultCommand(
                runId,
                explorationId,
                0,
                JourneyResultEngine.BASELINE,
                WorkerDegradedReason.AI_TIMEOUT,
                List.of("place:001", "place:002"),
                "INITIAL_BOARD"));

        assertThat(result).isEqualTo(PersistJourneyResultResult.PERSISTED);
        assertThat(queryString("""
                SELECT board->>'engine'
                FROM onmaru.discovery_explorations
                WHERE id = '%s'
                """.formatted(explorationId))).isEqualTo("BASELINE");
        assertThat(queryString("""
                SELECT board->>'degradedReason'
                FROM onmaru.discovery_explorations
                WHERE id = '%s'
                """.formatted(explorationId))).isEqualTo("AI_TIMEOUT");
        assertThat(queryInt("""
                SELECT state_version
                FROM onmaru.discovery_explorations
                WHERE id = '%s'
                """.formatted(explorationId))).isEqualTo(1);
    }

    @Test
    void discardsTerminalRunWithoutWritingBoard() throws Exception {
        updateRunStatus("CANCELLED");

        var result = store.persist(command());

        assertThat(result).isEqualTo(PersistJourneyResultResult.DISCARDED_TERMINAL);
        assertThat(queryString("""
                SELECT board::text
                FROM onmaru.discovery_explorations
                WHERE id = '%s'
                """.formatted(explorationId))).isNull();
    }

    @Test
    void discardsExpiredRunWithoutWritingBoard() throws Exception {
        expireRun();

        var result = store.persist(command());

        assertThat(result).isEqualTo(PersistJourneyResultResult.DISCARDED_EXPIRED);
        assertThat(queryString("""
                SELECT board::text
                FROM onmaru.discovery_explorations
                WHERE id = '%s'
                """.formatted(explorationId))).isNull();
    }

    private PersistJourneyResultCommand command() {
        return new PersistJourneyResultCommand(
                runId,
                explorationId,
                0,
                JourneyResultEngine.BASELINE,
                WorkerDegradedReason.AI_TIMEOUT,
                List.of("place:001"),
                "INITIAL_BOARD");
    }

    private void insertFixture(String status, String deadlineOffset) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.identity_members (id, status, created_at)
                    VALUES ('%s', 'ACTIVE', CURRENT_TIMESTAMP)
                    """.formatted(memberId));
            statement.execute("""
                    INSERT INTO onmaru.discovery_explorations (
                        id, owner_member_id, state_version, pinned_refs, excluded_refs, created_at, updated_at
                    ) VALUES ('%s', '%s', 0, '[]'::jsonb, '[]'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(explorationId, memberId));
            statement.execute("""
                    INSERT INTO onmaru.discovery_runs (
                        id, exploration_id, actor_key, base_version, status, stage, outcome,
                        created_at, deadline_at, started_at, generation, error_code, engine
                    ) VALUES (
                        '%s', '%s', 'actor:member:%s', 0, '%s', 'PERSISTING', NULL,
                        CURRENT_TIMESTAMP - INTERVAL '1 second',
                        CURRENT_TIMESTAMP + INTERVAL '%s',
                        CURRENT_TIMESTAMP, 4, NULL, 'LLM'
                    )
                    """.formatted(runId, explorationId, memberId, status, deadlineOffset));
        }
    }

    private void updateRunStatus(String status) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    UPDATE onmaru.discovery_runs
                    SET status = '%s', stage = NULL
                    WHERE id = '%s'
                    """.formatted(status, runId));
        }
    }

    private void expireRun() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    UPDATE onmaru.discovery_runs
                    SET deadline_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                    WHERE id = '%s'
                    """.formatted(runId));
        }
    }

    private String queryString(String sql) {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int queryInt(String sql) {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(), postgres.getMappedPort(POSTGRES_PORT), DATABASE);
    }

    private record DriverManagerDataSource(String url, String username, String password) implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public Connection getConnection(String suppliedUsername, String suppliedPassword) throws SQLException {
            return DriverManager.getConnection(url, suppliedUsername, suppliedPassword);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return DriverManager.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            DriverManager.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            DriverManager.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return DriverManager.getLoginTimeout();
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
    }
}

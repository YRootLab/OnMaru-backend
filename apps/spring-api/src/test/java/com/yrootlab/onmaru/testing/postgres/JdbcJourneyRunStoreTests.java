package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.journey.run.ActiveRunConflictException;
import com.yrootlab.onmaru.journey.run.AdvanceRunStageCommand;
import com.yrootlab.onmaru.journey.run.ClaimRunCommand;
import com.yrootlab.onmaru.journey.run.CreateRunCommand;
import com.yrootlab.onmaru.journey.run.FinishRunCommand;
import com.yrootlab.onmaru.journey.run.JourneyRunService;
import com.yrootlab.onmaru.journey.run.JourneyRunStage;
import com.yrootlab.onmaru.journey.run.JourneyRunStatus;
import com.yrootlab.onmaru.journey.run.RunCommandConflictException;
import com.yrootlab.onmaru.journey.run.RunTransitionConflictException;
import com.yrootlab.onmaru.persistence.journey.run.JdbcJourneyRunStore;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcJourneyRunStoreTests {

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
    private JourneyRunService service;
    private UUID explorationId;
    private String actorKey;

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
        service = new JourneyRunService(new JdbcJourneyRunStore(dataSource));
        var memberId = UUID.randomUUID();
        explorationId = UUID.randomUUID();
        actorKey = "actor:member:" + memberId;
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
        }
    }

    @Test
    void persistsStateMachineAndReadsSnapshotFromANewAdapter() {
        var runId = UUID.randomUUID();
        var created = create(runId, UUID.randomUUID(), "create-hash");
        assertThat(created.replayed()).isFalse();
        assertThat(created.receipt().generation()).isEqualTo(1);

        var claimed = service.claim(new ClaimRunCommand(
                UUID.randomUUID(), actorKey, runId, "claim-hash", 1, Instant.parse("2026-09-16T01:00:01Z")));
        assertThat(claimed.receipt().status()).isEqualTo(JourneyRunStatus.RUNNING);
        assertThat(claimed.receipt().generation()).isEqualTo(2);

        var generation = 2;
        JourneyRunStage previous = null;
        for (var next : JourneyRunStage.values()) {
            var advanced = service.advance(new AdvanceRunStageCommand(
                    UUID.randomUUID(), actorKey, runId, "advance-" + next, generation, previous, next));
            assertThat(advanced.receipt().stage()).isEqualTo(next);
            generation = advanced.receipt().generation();
            previous = next;
        }
        var completed = service.finish(new FinishRunCommand(
                UUID.randomUUID(), actorKey, runId, "finish-hash", generation,
                JourneyRunStatus.COMPLETED, "INITIAL_BOARD", null, Instant.parse("2026-09-16T01:00:05Z")));
        assertThat(completed.receipt().status()).isEqualTo(JourneyRunStatus.COMPLETED);
        assertThat(completed.receipt().generation()).isEqualTo(7);

        var snapshot = new JourneyRunService(new JdbcJourneyRunStore(dataSource)).find(runId, actorKey).orElseThrow();
        assertThat(snapshot.status()).isEqualTo(JourneyRunStatus.COMPLETED);
        assertThat(snapshot.outcome()).isEqualTo("INITIAL_BOARD");
        assertThat(snapshot.stage()).isNull();
        assertThat(snapshot.generation()).isEqualTo(7);
    }

    @Test
    void concurrentlyReplaysSameCommandExactlyOnce() throws Exception {
        var runId = UUID.randomUUID();
        var commandKey = UUID.randomUUID();
        var gate = new CountDownLatch(1);
        Callable<Boolean> invocation = () -> {
            gate.await(5, TimeUnit.SECONDS);
            return create(runId, commandKey, "same-hash").replayed();
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(invocation);
            var second = executor.submit(invocation);
            gate.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(false, true);
        }
        assertThat(count("SELECT count(*) FROM onmaru.discovery_runs")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM onmaru.discovery_run_commands")).isEqualTo(1);
    }

    @Test
    void rejectsReusedCommandKeyWithDifferentPayload() {
        var commandKey = UUID.randomUUID();
        create(UUID.randomUUID(), commandKey, "first-hash");

        assertThatThrownBy(() -> create(UUID.randomUUID(), commandKey, "different-hash"))
                .isInstanceOf(RunCommandConflictException.class);
        assertThat(count("SELECT count(*) FROM onmaru.discovery_runs")).isEqualTo(1);
    }

    @Test
    void enforcesOneActiveRunPerExploration() {
        create(UUID.randomUUID(), UUID.randomUUID(), "first-hash");

        assertThatThrownBy(() -> create(UUID.randomUUID(), UUID.randomUUID(), "second-hash"))
                .isInstanceOf(ActiveRunConflictException.class);
        assertThat(count("SELECT count(*) FROM onmaru.discovery_runs WHERE status IN ('QUEUED', 'RUNNING')"))
                .isEqualTo(1);
    }

    @Test
    void cancelAndCompleteRaceAllowsOneTerminalWinner() throws Exception {
        var runId = UUID.randomUUID();
        create(runId, UUID.randomUUID(), "create-hash");
        service.claim(new ClaimRunCommand(
                UUID.randomUUID(), actorKey, runId, "claim-hash", 1, Instant.parse("2026-09-16T01:00:01Z")));
        var gate = new CountDownLatch(1);
        Callable<Object> cancel = () -> finishRace(gate, new FinishRunCommand(
                UUID.randomUUID(), actorKey, runId, "cancel-hash", 2,
                JourneyRunStatus.CANCELLED, null, null, Instant.parse("2026-09-16T01:00:02Z")));
        Callable<Object> complete = () -> finishRace(gate, new FinishRunCommand(
                UUID.randomUUID(), actorKey, runId, "complete-hash", 2,
                JourneyRunStatus.COMPLETED, "INITIAL_BOARD", null, Instant.parse("2026-09-16T01:00:02Z")));

        List<Object> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(cancel);
            var second = executor.submit(complete);
            gate.countDown();
            results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertThat(results).allMatch(result -> result instanceof JourneyRunStatus);
        assertThat(results.stream().distinct().count()).isEqualTo(1);
        assertThat(service.find(runId, actorKey).orElseThrow().status()).isIn(
                JourneyRunStatus.CANCELLED, JourneyRunStatus.COMPLETED);
        assertThat(count("SELECT count(*) FROM onmaru.discovery_run_commands WHERE operation = 'FINISH'"))
                .isEqualTo(2);
    }

    @Test
    void rejectsStaleGenerationAndSkippedDatabaseStage() {
        var runId = UUID.randomUUID();
        create(runId, UUID.randomUUID(), "create-hash");
        service.claim(new ClaimRunCommand(
                UUID.randomUUID(), actorKey, runId, "claim-hash", 1, Instant.parse("2026-09-16T01:00:01Z")));

        assertThatThrownBy(() -> service.claim(new ClaimRunCommand(
                UUID.randomUUID(), actorKey, runId, "stale-hash", 1, Instant.parse("2026-09-16T01:00:02Z"))))
                .isInstanceOf(RunTransitionConflictException.class);
        assertThatThrownBy(() -> service.advance(new AdvanceRunStageCommand(
                UUID.randomUUID(), actorKey, runId, "wrong-stage-hash", 2,
                JourneyRunStage.INTERPRETING, JourneyRunStage.RETRIEVING)))
                .isInstanceOf(RunTransitionConflictException.class);
    }

    private com.yrootlab.onmaru.journey.run.RunCommandResult create(
            UUID runId, UUID commandKey, String requestHash) {
        return service.create(new CreateRunCommand(
                commandKey, actorKey, requestHash, runId, explorationId, 0, "LLM",
                Instant.parse("2026-09-16T01:00:00Z"), Instant.parse("2026-09-16T01:00:30Z")));
    }

    private Object finishRace(CountDownLatch gate, FinishRunCommand command) throws InterruptedException {
        gate.await(5, TimeUnit.SECONDS);
        try {
            return service.finish(command).receipt().status();
        } catch (RunTransitionConflictException exception) {
            return exception;
        }
    }

    private int count(String sql) {
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
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
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
    }
}

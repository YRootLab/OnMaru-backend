package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.integration.ai.progress.AiRunProgressStore.Decision;
import com.yrootlab.onmaru.integration.ai.progress.JdbcAiRunProgressStore;
import com.yrootlab.onmaru.journey.run.JourneyRunService;
import com.yrootlab.onmaru.journey.run.JourneyRunStage;
import com.yrootlab.onmaru.journey.run.JourneyRunStatus;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcAiRunProgressStoreTests {

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

    private static final Instant T0 = Instant.parse("2026-11-09T00:00:00Z");
    private static final String PLAN_HASH = "sha256:" + "a".repeat(64);
    private static final String OTHER_PLAN_HASH = "sha256:" + "b".repeat(64);

    private DataSource dataSource;
    private UUID runId;
    private String actorKey;
    private JdbcAiRunProgressStore store;

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
        runId = UUID.randomUUID();
        var memberId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
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
            statement.execute("""
                    INSERT INTO onmaru.discovery_runs (
                        id, exploration_id, actor_key, base_version, status, created_at, deadline_at, generation, engine
                    ) VALUES ('%s', '%s', '%s', 0, 'RUNNING', '%s', '%s', 2, 'ai-engine')
                    """.formatted(runId, explorationId, actorKey, T0.minusSeconds(60), T0.plusSeconds(300)));
        }
        store = new JdbcAiRunProgressStore(dataSource, Clock.fixed(T0, ZoneOffset.UTC));
        store.bind(binding(runId, PLAN_HASH), T0.plusSeconds(300));
    }

    @Test
    void ordinalCompareAndSetRejectsReplayStaleAndConflictingCallbacks() {
        var first = store.record(runId, PLAN_HASH, 1, JourneyRunStage.INTERPRETING);
        assertThat(first.decision()).isEqualTo(Decision.APPLIED);
        assertThat(first.publish()).isEqualTo(JourneyRunStage.INTERPRETING);

        assertThat(store.record(runId, PLAN_HASH, 1, JourneyRunStage.INTERPRETING).decision()).isEqualTo(Decision.DUPLICATE);
        assertThat(store.record(runId, PLAN_HASH, 1, JourneyRunStage.RETRIEVING).decision()).isEqualTo(Decision.CONFLICT);

        var second = store.record(runId, PLAN_HASH, 2, JourneyRunStage.RETRIEVING);
        assertThat(second.decision()).isEqualTo(Decision.APPLIED);
        assertThat(second.publish()).isEqualTo(JourneyRunStage.RETRIEVING);

        assertThat(store.record(runId, PLAN_HASH, 1, JourneyRunStage.RETRIEVING).decision()).isEqualTo(Decision.STALE);
        assertThat(store.record(runId, PLAN_HASH, 3, JourneyRunStage.INTERPRETING).decision()).isEqualTo(Decision.CONFLICT);

        var sameStage = store.record(runId, PLAN_HASH, 3, JourneyRunStage.RETRIEVING);
        assertThat(sameStage.decision()).isEqualTo(Decision.APPLIED);
        assertThat(sameStage.publish()).isNull();

        var advanced = store.record(runId, PLAN_HASH, 4, JourneyRunStage.VALIDATING);
        assertThat(advanced.publish()).isEqualTo(JourneyRunStage.VALIDATING);
    }

    @Test
    void planMismatchAndUnboundRunsAreNotApplied() {
        assertThat(store.record(runId, OTHER_PLAN_HASH, 1, JourneyRunStage.INTERPRETING).decision())
                .isEqualTo(Decision.PLAN_MISMATCH);
        assertThat(store.record(UUID.randomUUID(), PLAN_HASH, 1, JourneyRunStage.INTERPRETING).decision())
                .isEqualTo(Decision.NOT_BOUND);
        assertThat(store.boundPlanHash(runId)).contains(PLAN_HASH);
    }

    @Test
    void terminalRunIgnoresCallbacksWithoutRegressingState() throws Exception {
        store.record(runId, PLAN_HASH, 1, JourneyRunStage.INTERPRETING);
        execute("UPDATE onmaru.discovery_runs SET status = 'CANCELLED' WHERE id = '%s'".formatted(runId));

        var result = store.record(runId, PLAN_HASH, 2, JourneyRunStage.RETRIEVING);
        assertThat(result.decision()).isEqualTo(Decision.INACTIVE);
        assertThat(result.publish()).isNull();
        assertThat(lastOrdinal()).isEqualTo(1);
    }

    @Test
    void expiredBindingIsInactiveAndCannotBeRebound() {
        var late = new JdbcAiRunProgressStore(dataSource, Clock.fixed(T0.plusSeconds(400), ZoneOffset.UTC));
        assertThat(late.record(runId, PLAN_HASH, 1, JourneyRunStage.INTERPRETING).decision()).isEqualTo(Decision.INACTIVE);
        assertThatThrownBy(() -> store.bind(binding(runId, OTHER_PLAN_HASH), T0.plusSeconds(300)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bindingReplayIsIdempotentOnlyForTheSamePlan() {
        store.bind(binding(runId, PLAN_HASH), T0.plusSeconds(300));
        assertThat(store.boundPlanHash(runId)).contains(PLAN_HASH);
    }

    @Test
    void ordinalSurvivesRestartOfTheStoreInstance() {
        store.record(runId, PLAN_HASH, 1, JourneyRunStage.INTERPRETING);
        store.record(runId, PLAN_HASH, 2, JourneyRunStage.RETRIEVING);

        var restarted = new JdbcAiRunProgressStore(dataSource, Clock.fixed(T0, ZoneOffset.UTC));
        assertThat(restarted.record(runId, PLAN_HASH, 2, JourneyRunStage.RETRIEVING).decision()).isEqualTo(Decision.DUPLICATE);
        assertThat(restarted.record(runId, PLAN_HASH, 1, JourneyRunStage.INTERPRETING).decision()).isEqualTo(Decision.STALE);
    }

    @Test
    void snapshotShowsTheSameEffectiveStageAsThePublisher() throws Exception {
        var snapshots = new JdbcJourneyRunStore(dataSource);

        // Worker has already entered VALIDATING before the AI call; an earlier AI stage must not move the snapshot back.
        execute("UPDATE onmaru.discovery_runs SET stage = 'VALIDATING' WHERE id = '%s'".formatted(runId));
        var early = store.record(runId, PLAN_HASH, 1, JourneyRunStage.INTERPRETING);
        assertThat(early.publish()).isNull();
        assertThat(snapshots.find(runId, actorKey).orElseThrow().stage()).isEqualTo(JourneyRunStage.VALIDATING);

        // A stage the worker has not reached yet is shown and published as soon as AI reports it.
        execute("UPDATE onmaru.discovery_runs SET stage = NULL WHERE id = '%s'".formatted(runId));
        var fresh = store.record(runId, PLAN_HASH, 2, JourneyRunStage.RETRIEVING);
        assertThat(fresh.publish()).isEqualTo(JourneyRunStage.RETRIEVING);
        assertThat(snapshots.find(runId, actorKey).orElseThrow().stage()).isEqualTo(JourneyRunStage.RETRIEVING);

        var completed = snapshots.find(runId, actorKey).orElseThrow();
        assertThat(completed.status()).isEqualTo(JourneyRunStatus.RUNNING);
    }

    private static ApprovedPlanBinding binding(UUID runId, String planHash) {
        var versions = new Versions("corpus-1", "sha256:" + "c".repeat(64), "dict-1", 7);
        var filters = new Filters("sha256:" + "d".repeat(64), true, List.of("place-1"),
                List.of("SEOUL"), List.of("HANOK"), List.of(), List.of());
        return new ApprovedPlanBinding(new PlanProposal("ai-plan.v1", "1", runId,
                Route.SQL_ONLY, RetrievalMode.NONE, List.of(), null, null, List.of(CanonicalField.PUBLIC_ADDRESS),
                filters, null, versions, new Limits(0, 0, 0, 0, 5), null, planHash));
    }

    private int lastOrdinal() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT last_ordinal FROM onmaru.journey_run_ai_bindings WHERE run_id = '" + runId + "'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
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

package com.yrootlab.onmaru.testing.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.journey.observation.ObservationOutcome;
import com.yrootlab.onmaru.journey.observation.ObservationRoute;
import com.yrootlab.onmaru.journey.observation.ObservationStatus;
import com.yrootlab.onmaru.journey.observation.RunObservation;
import com.yrootlab.onmaru.journey.observation.StageExecutionStatus;
import com.yrootlab.onmaru.journey.observation.StageObservation;
import com.yrootlab.onmaru.journey.observation.TraceCoverage;
import com.yrootlab.onmaru.persistence.journey.observation.JdbcRunObservationStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRunObservationStoreTests {

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
    private JdbcRunObservationStore store;
    private UUID runId;

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(POSTGRES_PORT) + "/" + DATABASE;
    }

    @BeforeAll
    static void startContainer() {
        postgres.start();
    }

    @AfterAll
    static void stopContainer() {
        postgres.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
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

        dataSource = new SimpleDataSource(jdbcUrl(), USERNAME, PASSWORD);
        store = new JdbcRunObservationStore(dataSource, new ObjectMapper());

        var memberId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        runId = UUID.randomUUID();

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
                        '%s', '%s', 'actor:member:%s', 0, 'COMPLETED', NULL, 'INITIAL_BOARD',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '60 seconds',
                        CURRENT_TIMESTAMP, 1, NULL, 'LLM'
                    )
                    """.formatted(runId, explorationId, memberId));
        }
    }

    @Test
    @DisplayName("RunObservation을 저장하고 runId로 조회할 수 있으며 stages가 정상 보존된다")
    void savesAndRetrievesRunObservation() {
        var stage1 = StageObservation.of(
                "RETRIEVAL",
                StageExecutionStatus.SUCCESS,
                null,
                null,
                150,
                10,
                5
        );
        var stage2 = StageObservation.of(
                "GENERATION",
                StageExecutionStatus.SUCCESS,
                null,
                null,
                450,
                null,
                null
        );

        var observation = RunObservation.builder(runId)
                .traceId("4bf92f3577b34da6a3ce929d0e0e4736")
                .observationStatus(ObservationStatus.COMPLETE)
                .traceCoverage(TraceCoverage.AVAILABLE)
                .route(ObservationRoute.HYBRID)
                .outcome(ObservationOutcome.SUCCESS)
                .corpusRevision("corpus-20261105")
                .configHash("cfg-hash-abc")
                .promptVersion("prompt-v1")
                .stages(List.of(stage1, stage2))
                .build();

        store.save(observation);

        // findByRunId
        var foundByRun = store.findByRunId(runId);
        assertThat(foundByRun).isPresent();
        var retrieved = foundByRun.get();
        assertThat(retrieved.runId()).isEqualTo(runId);
        assertThat(retrieved.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(retrieved.observationStatus()).isEqualTo(ObservationStatus.COMPLETE);
        assertThat(retrieved.traceCoverage()).isEqualTo(TraceCoverage.AVAILABLE);
        assertThat(retrieved.route()).isEqualTo(ObservationRoute.HYBRID);
        assertThat(retrieved.outcome()).isEqualTo(ObservationOutcome.SUCCESS);
        assertThat(retrieved.corpusRevision()).isEqualTo("corpus-20261105");
        assertThat(retrieved.stages()).hasSize(2);
        assertThat(retrieved.stages().get(0).stage()).isEqualTo("RETRIEVAL");
        assertThat(retrieved.stages().get(0).durationMs()).isEqualTo(150);
    }

    @Test
    @DisplayName("RunObservation 저장 시 민감 정보(query, prompt 전문, credential 등)는 컬럼에 저장되지 않는다")
    void assertsSanitizedProjectionSchema() throws Exception {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                     SELECT column_name
                     FROM information_schema.columns
                     WHERE table_schema = 'onmaru' AND table_name = 'discovery_run_observations'
                     """)) {
            var rs = stmt.executeQuery();
            List<String> columnNames = new java.util.ArrayList<>();
            while (rs.next()) {
                columnNames.add(rs.getString("column_name").toLowerCase());
            }

            assertThat(columnNames).contains(
                    "run_id", "trace_id", "observation_status", "trace_coverage",
                    "route", "outcome", "corpus_revision", "config_hash",
                    "prompt_version", "stages", "created_at", "updated_at"
            );
            // Assert no raw query, answer text, prompt or token columns
            assertThat(columnNames).doesNotContain(
                    "query", "prompt", "answer", "raw_query", "tokens",
                    "member_id", "user_id", "actor_key", "secret", "token"
            );
        }
    }

    private static final class SimpleDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;

        SimpleDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("unwrap not supported");
        }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}

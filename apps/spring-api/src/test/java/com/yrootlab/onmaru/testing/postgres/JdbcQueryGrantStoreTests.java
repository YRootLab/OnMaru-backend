package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.integration.ai.grant.JdbcQueryGrantStore;
import com.yrootlab.onmaru.integration.ai.grant.QueryGrant;
import com.yrootlab.onmaru.integration.ai.grant.QueryGrantException;
import com.yrootlab.onmaru.integration.ai.grant.QueryGrantService;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.CanonicalField;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Filters;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Versions;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcQueryGrantStoreTests {

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
    private static final String REFERENCE = "qg_" + "A".repeat(43);

    private DataSource dataSource;
    private JdbcQueryGrantStore store;
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
        store = new JdbcQueryGrantStore(dataSource);
        runId = UUID.randomUUID();
        var memberId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
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
                    ) VALUES ('%s', '%s', 'actor:member:%s', 0, 'RUNNING', '%s', '%s', 2, 'ai-engine')
                    """.formatted(runId, explorationId, memberId,
                    T0.minusSeconds(60), T0.plusSeconds(300)));
        }
        store.insert(grant(QueryGrantService.hash(REFERENCE), T0.plusSeconds(30)));
    }

    @Test
    void storesOnlyHashAndConsumesTheGrantExactlyOnce() throws Exception {
        String value = store.consume(QueryGrantService.hash(REFERENCE), T0.plusSeconds(1), g -> "ok");
        assertThat(value).isEqualTo("ok");

        assertThatThrownBy(() -> store.consume(QueryGrantService.hash(REFERENCE), T0.plusSeconds(2), g -> "again"))
                .isInstanceOfSatisfying(QueryGrantException.class,
                        e -> assertThat(e.code()).isEqualTo(QueryGrantException.Code.GRANT_CONSUMED));
        assertThat(countRowsContaining(REFERENCE)).isZero();
    }

    @Test
    void scopeRejectionOrLookupFailureRollsBackAndKeepsGrantUsable() {
        assertThatThrownBy(() -> store.consume(QueryGrantService.hash(REFERENCE), T0.plusSeconds(1),
                g -> { throw new QueryGrantException(QueryGrantException.Code.GRANT_SCOPE_REJECTED); }))
                .isInstanceOfSatisfying(QueryGrantException.class,
                        e -> assertThat(e.code()).isEqualTo(QueryGrantException.Code.GRANT_SCOPE_REJECTED));
        assertThatThrownBy(() -> store.consume(QueryGrantService.hash(REFERENCE), T0.plusSeconds(1),
                g -> { throw new IllegalStateException("upstream failed"); }))
                .isInstanceOf(IllegalStateException.class);

        String value = store.consume(QueryGrantService.hash(REFERENCE), T0.plusSeconds(2), g -> "ok");
        assertThat(value).isEqualTo("ok");
    }

    @Test
    void rejectsUnknownExpiredAndInactiveRunsWithoutConsuming() throws Exception {
        assertThatThrownBy(() -> store.consume(QueryGrantService.hash("qg_" + "B".repeat(43)), T0, g -> "x"))
                .isInstanceOfSatisfying(QueryGrantException.class,
                        e -> assertThat(e.code()).isEqualTo(QueryGrantException.Code.GRANT_INVALID));

        assertThatThrownBy(() -> store.consume(QueryGrantService.hash(REFERENCE), T0.plusSeconds(30), g -> "x"))
                .isInstanceOfSatisfying(QueryGrantException.class,
                        e -> assertThat(e.code()).isEqualTo(QueryGrantException.Code.GRANT_EXPIRED));

        execute("UPDATE onmaru.discovery_runs SET status = 'CANCELLED' WHERE id = '%s'"
                .formatted(runId));
        assertThatThrownBy(() -> store.consume(QueryGrantService.hash(REFERENCE), T0.plusSeconds(1), g -> "x"))
                .isInstanceOfSatisfying(QueryGrantException.class,
                        e -> assertThat(e.code()).isEqualTo(QueryGrantException.Code.RUN_INACTIVE));
        assertThat(consumedAtIsNull()).isTrue();
    }

    @Test
    void allowsOneGrantPerRunAndRaceHasSingleWinner() throws Exception {
        assertThatThrownBy(() -> store.insert(grant(QueryGrantService.hash("qg_" + "C".repeat(43)), T0.plusSeconds(30))))
                .isInstanceOfSatisfying(QueryGrantException.class,
                        e -> assertThat(e.code()).isEqualTo(QueryGrantException.Code.GRANT_ALREADY_ISSUED));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            var start = new CountDownLatch(1);
            List<Future<Boolean>> attempts = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                attempts.add(pool.submit((Callable<Boolean>) () -> {
                    start.await();
                    try {
                        store.consume(QueryGrantService.hash(REFERENCE), T0.plusSeconds(1), g -> {
                            sleep();
                            return "ok";
                        });
                        return true;
                    } catch (QueryGrantException exception) {
                        return false;
                    }
                }));
            }
            start.countDown();
            long winners = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(30, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private QueryGrant grant(String tokenHash, Instant expiresAt) {
        var filters = new Filters("sha256:" + "b".repeat(64), true, List.of("place-1"),
                List.of("SEOUL"), List.of("HANOK"), List.of(), List.of());
        return new QueryGrant(tokenHash, runId, "sha256:" + "a".repeat(64), filters,
                List.of(CanonicalField.PUBLIC_ADDRESS),
                new Versions("corpus-1", "sha256:" + "c".repeat(64), "dict-1", 7),
                5, T0, expiresAt);
    }

    private int countRowsContaining(String plaintext) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT count(*) FROM onmaru.journey_query_grants
                     WHERE token_hash = ? OR plan_hash = ? OR filters::text LIKE ?
                     """)) {
            statement.setString(1, plaintext);
            statement.setString(2, plaintext);
            statement.setString(3, "%" + plaintext + "%");
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private boolean consumedAtIsNull() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT consumed_at FROM onmaru.journey_query_grants")) {
            rows.next();
            return rows.getObject(1) == null;
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

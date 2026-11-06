package com.yrootlab.onmaru.testing.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.journey.exploration.ExplorationActor;
import com.yrootlab.onmaru.journey.exploration.ExplorationRun;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunStatus;
import com.yrootlab.onmaru.journey.exploration.ExplorationState;
import com.yrootlab.onmaru.journey.exploration.StoredExplorationTurn;
import com.yrootlab.onmaru.persistence.journey.exploration.JdbcExplorationStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcExplorationStoreIntegrationTests {

    private static final int POSTGRES_PORT = 5432;
    private static final String DATABASE = "onmaru_test";
    private static final String USERNAME = "onmaru_test";
    private static final String PASSWORD = "onmaru_test";
    private static final String GUEST_TOKEN_HASH =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

    private static final GenericContainer<?> postgres = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(POSTGRES_PORT)
            .withEnv("POSTGRES_DB", DATABASE)
            .withEnv("POSTGRES_USER", USERNAME)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private JdbcExplorationStore store;
    private DataSource dataSource;

    @BeforeAll
    static void startPostgres() {
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        resetAndMigrate();
        this.dataSource = new DriverManagerDataSource(jdbcUrl(), USERNAME, PASSWORD);
        this.store = new JdbcExplorationStore(dataSource, new ObjectMapper());
    }

    @Test
    void persistsExplorationAndRestoresAcrossNewStoreInstance() throws Exception {
        var memberId = UUID.randomUUID();
        insertMember(memberId);

        var actor = ExplorationActor.member(memberId);
        var explorationId = UUID.randomUUID();
        var initialTurnId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var now = Instant.now();

        var run = new ExplorationRun(
                runId,
                ExplorationRunStatus.QUEUED,
                "BASELINE",
                "INTERPRETING",
                null,
                null,
                now,
                null,
                now.plusSeconds(20)
        );
        var state = new ExplorationState(explorationId, actor, 0, "jeonju", run, now);

        store.create(state, initialTurnId, "전주 한옥마을 당일치기 코스 추천해줘", now);

        // Simulate restart by creating a fresh store instance
        var freshStore = new JdbcExplorationStore(dataSource, new ObjectMapper());
        var loaded = freshStore.find(explorationId);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().id()).isEqualTo(explorationId);
        assertThat(loaded.get().owner()).isEqualTo(actor);
        assertThat(loaded.get().latestRun()).isNotNull();
        assertThat(loaded.get().latestRun().id()).isEqualTo(runId);
        assertThat(loaded.get().latestRun().status()).isEqualTo(ExplorationRunStatus.QUEUED);

        var turns = freshStore.listTurns(explorationId, 20, null);
        assertThat(turns.items()).hasSize(1);
        assertThat(turns.items().get(0).query()).isEqualTo("전주 한옥마을 당일치기 코스 추천해줘");
        assertThat(turns.items().get(0).turnId()).isEqualTo(initialTurnId);

        var list = freshStore.list(actor, 20, null, now);
        assertThat(list.items()).hasSize(1);
        assertThat(list.items().get(0).explorationId()).isEqualTo(explorationId);
        assertThat(list.items().get(0).lastUserQueryPreview()).contains("전주 한옥마을");
    }

    @Test
    void memberDeletionHidesImmediatelyAndCancelsActiveRuns() throws Exception {
        var memberId = UUID.randomUUID();
        insertMember(memberId);

        var actor = ExplorationActor.member(memberId);
        var explorationId = UUID.randomUUID();
        var initialTurnId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var now = Instant.now();

        var run = new ExplorationRun(
                runId,
                ExplorationRunStatus.QUEUED,
                "BASELINE",
                "INTERPRETING",
                null,
                null,
                now,
                null,
                now.plusSeconds(20)
        );
        var state = new ExplorationState(explorationId, actor, 0, "jeonju", run, now);

        store.create(state, initialTurnId, "전주 여행", now);

        boolean deleted = store.delete(actor, explorationId, now);
        assertThat(deleted).isTrue();

        assertThat(store.find(explorationId)).isEmpty();
        var list = store.list(actor, 20, null, now);
        assertThat(list.items()).isEmpty();
    }

    @Test
    void guestExpiresAfter24Hours() throws Exception {
        var guestId = UUID.randomUUID();
        insertGuest(guestId);

        var actor = ExplorationActor.guest(guestId.toString());
        var explorationId = UUID.randomUUID();
        var initialTurnId = UUID.randomUUID();
        var now = Instant.now();

        var run = new ExplorationRun(
                UUID.randomUUID(),
                ExplorationRunStatus.QUEUED,
                "BASELINE",
                "INTERPRETING",
                null,
                null,
                now,
                null,
                now.plusSeconds(20)
        );
        var state = new ExplorationState(explorationId, actor, 0, "jeonju", run, now);

        store.create(state, initialTurnId, "게스트 질문", now);

        // Before 24h: visible
        var listBefore = store.list(actor, 20, null, now.plus(Duration.ofHours(23)));
        assertThat(listBefore.items()).hasSize(1);

        // Run expiration cleaner at 25h
        store.deleteExpiredGuests(now.plus(Duration.ofHours(25)));

        // After 25h: hidden / expired
        var listAfter = store.list(actor, 20, null, now.plus(Duration.ofHours(25)));
        assertThat(listAfter.items()).isEmpty();
    }

    private void insertMember(UUID memberId) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.identity_members (id, status, created_at)
                    VALUES ('%s', 'ACTIVE', CURRENT_TIMESTAMP)
                    """.formatted(memberId, memberId));
        }
    }

    private void insertGuest(UUID guestId) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.identity_guests (id, token_hash, expires_at)
                    VALUES ('%s', '%s', CURRENT_TIMESTAMP + interval '1 day')
                    """.formatted(guestId, GUEST_TOKEN_HASH));
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

    private static String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(),
                postgres.getMappedPort(POSTGRES_PORT),
                DATABASE);
    }

    private record DriverManagerDataSource(String url, String username, String password) implements DataSource {
        @Override
        public java.sql.Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public java.sql.Connection getConnection(String suppliedUsername, String suppliedPassword) throws SQLException {
            return DriverManager.getConnection(url, suppliedUsername, suppliedPassword);
        }

        @Override
        public java.io.PrintWriter getLogWriter() { return null; }
        @Override
        public void setLogWriter(java.io.PrintWriter out) {}
        @Override
        public void setLoginTimeout(int seconds) {}
        @Override
        public int getLoginTimeout() { return 0; }
        @Override
        public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override
        public <T> T unwrap(Class<T> iface) { return null; }
        @Override
        public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}

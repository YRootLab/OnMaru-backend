package com.yrootlab.onmaru.testing.postgres;

import org.flywaydb.core.Flyway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.journey.exploration.CreateExplorationCommand;
import com.yrootlab.onmaru.journey.exploration.ExplorationActor;
import com.yrootlab.onmaru.journey.exploration.ExplorationActorType;
import com.yrootlab.onmaru.journey.exploration.ExplorationRun;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunRequest;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunStatus;
import com.yrootlab.onmaru.journey.exploration.ExplorationService;
import com.yrootlab.onmaru.journey.exploration.InMemoryExplorationRunDispatcher;
import com.yrootlab.onmaru.journey.run.JourneyRunStatus;
import com.yrootlab.onmaru.journey.worker.AiProposalException;
import com.yrootlab.onmaru.journey.worker.InMemoryJourneyCandidateProvider;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerPlan;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRunner;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerService;
import com.yrootlab.onmaru.journey.worker.WorkerDegradedReason;
import com.yrootlab.onmaru.persistence.journey.exploration.JdbcExplorationStore;
import com.yrootlab.onmaru.persistence.journey.run.JdbcJourneyRunStore;
import com.yrootlab.onmaru.persistence.journey.worker.JdbcJourneyResultStore;
import com.yrootlab.onmaru.persistence.journey.worker.JdbcJourneyWorkerQueue;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.DriverManager;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRequest;

/**
 * Durable exploration path: a run created by the exploration store is queued as a discovery_runs row, polled
 * from that row, processed by the worker, and visible as COMPLETED through the exploration read path.
 */
class JdbcDurableExplorationWorkerTests {

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

    private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");
    private static final String QUERY = "전주 한옥마을 근처 조용한 카페 추천해줘";

    private DataSource dataSource;
    private Clock clock;
    private ExplorationActor actor;

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
        // Result persistence checks the deadline with the database clock, so the app clock must be real time here.
        clock = Clock.systemUTC();
        var memberId = UUID.randomUUID();
        actor = new ExplorationActor(ExplorationActorType.MEMBER, memberId.toString());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.identity_members (id, status, created_at)
                    VALUES ('%s', 'ACTIVE', TIMESTAMP '2026-11-08 00:00:00')
                    """.formatted(memberId));
        }
        activeRevision();
    }

    @Test
    void createdExplorationRunIsPolledFromTheDatabaseAndWorkedToCompletion() throws Exception {
        var store = new JdbcExplorationStore(dataSource, new ObjectMapper());
        var dispatcher = new InMemoryExplorationRunDispatcher();
        var explorations = new ExplorationService(store, dispatcher, clock);

        var created = explorations.create(actor, new CreateExplorationCommand(QUERY, "ko-KR", "kr-45-jeonju"));
        var runId = created.run().id();
        assertThat(runStatus(runId)).isEqualTo("QUEUED");

        var queue = new JdbcJourneyWorkerQueue(dataSource, clock);
        var polled = queue.poll().orElseThrow();
        assertThat(polled.runId()).isEqualTo(runId);
        assertThat(polled.explorationId()).isEqualTo(created.explorationId());
        assertThat(polled.queryText()).isEqualTo(QUERY);
        assertThat(polled.regionCode()).isEqualTo("kr-45-jeonju");
        assertThat(polled.expectedGeneration()).isEqualTo(1);
        assertThat(polled.datasetRevision()).isNotBlank();
        assertThat(polled.deadlineAt()).isAfter(clock.instant());
        assertThat(polled.traceId()).matches("[0-9a-f]{32}");

        // The drain starts a fresh walk; the manual poll above only inspected the head of the queue.
        var drain = new JourneyWorkerRunner(new JdbcJourneyWorkerQueue(dataSource, clock), workerService()::process).drain();
        assertThat(drain.processed()).isEqualTo(1);
        assertThat(drain.outcomes()).as("worker outcome").isNotEmpty();
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");

        var run = explorations.getRun(actor, created.explorationId(), runId);
        assertThat(run.status()).isEqualTo(ExplorationRunStatus.COMPLETED);
        assertThat(queue.poll()).isEmpty();
        assertThat(new JourneyWorkerRunner(queue, workerService()::process).drain().processed()).isZero();
        assertThat(dispatcher.dispatchCount()).isEqualTo(1);
    }

    @Test
    void expiredQueuedRunsAreLeftForTheSweeperAndNotPolled() throws Exception {
        var store = new JdbcExplorationStore(dataSource, new ObjectMapper());
        var explorations = new ExplorationService(store, new InMemoryExplorationRunDispatcher(), clock);
        explorations.create(actor, new CreateExplorationCommand(QUERY, "ko-KR", "kr-45-jeonju"));

        var lateQueue = new JdbcJourneyWorkerQueue(dataSource, Clock.offset(clock, java.time.Duration.ofHours(2)));
        assertThat(lateQueue.poll()).isEmpty();
    }

    @Test
    void pollWalksQueuedRunsOnceThenResetsForTheNextDrain() throws Exception {
        var store = new JdbcExplorationStore(dataSource, new ObjectMapper());
        var explorations = new ExplorationService(store, new InMemoryExplorationRunDispatcher(), clock);
        // One active exploration per member is enforced by the database, so the two runs belong to two members.
        var first = explorations.create(actor, new CreateExplorationCommand(QUERY, "ko-KR", "kr-45-jeonju"));
        var otherActor = new ExplorationActor(ExplorationActorType.MEMBER, insertMember());
        var second = explorations.create(otherActor, new CreateExplorationCommand(QUERY, "ko-KR", "kr-45-jeonju"));

        var queue = new JdbcJourneyWorkerQueue(dataSource, clock);
        var seen = new java.util.ArrayList<UUID>();
        Optional<JourneyWorkerRequest> next;
        while ((next = queue.poll()).isPresent()) {
            seen.add(next.get().runId());
        }
        assertThat(seen).containsExactlyInAnyOrder(first.run().id(), second.run().id());
        assertThat(queue.poll().map(JourneyWorkerRequest::runId)).isPresent();
    }

    private JourneyWorkerService workerService() {
        var candidates = new InMemoryJourneyCandidateProvider();
        return new JourneyWorkerService(
                new JdbcJourneyRunStore(dataSource),
                candidates,
                (request, payload) -> {
                    throw new AiProposalException(WorkerDegradedReason.AI_SERVICE_UNAVAILABLE);
                },
                payload -> new JourneyWorkerPlan(List.of(), "INITIAL_BOARD"),
                new JdbcJourneyResultStore(dataSource),
                event -> { },
                com.yrootlab.onmaru.journey.events.JourneyRunEventSink.noop(),
                clock);
    }

    private void activeRevision() throws SQLException {
        var revision = UUID.randomUUID();
        var published = OffsetDateTime.of(2026, 11, 8, 0, 0, 0, 0, ZoneOffset.UTC);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.catalog_dataset_revisions (id, dataset, status, fetched_at, published_at)
                    VALUES ('%s', 'kto-korean-tour', 'PUBLISHED', '%s', '%s')
                    """.formatted(revision, published, published));
            statement.execute("""
                    INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                    VALUES ('kto-korean-tour', '%s', '%s')
                    """.formatted(revision, published));
        }
    }

    private String insertMember() throws SQLException {
        var memberId = UUID.randomUUID();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.identity_members (id, status, created_at)
                    VALUES ('%s', 'ACTIVE', TIMESTAMP '2026-11-08 00:00:00')
                    """.formatted(memberId));
        }
        return memberId.toString();
    }

    private String runStatus(UUID runId) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT status::text FROM onmaru.discovery_runs WHERE id = ?")) {
            statement.setObject(1, runId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
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

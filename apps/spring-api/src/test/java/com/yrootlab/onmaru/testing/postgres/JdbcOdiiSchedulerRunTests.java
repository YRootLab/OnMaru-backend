package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.audio.sync.*;
import com.yrootlab.onmaru.scheduling.audio.OdiiSyncSchedulingAdapter;
import com.yrootlab.onmaru.tourism.audio.JdbcAudioRevisionStore;
import com.yrootlab.onmaru.tourism.audio.OdiiClientConfiguration;
import com.yrootlab.onmaru.persistence.audio.AudioPersistenceConfiguration;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.config.secrets.SecretBundle;
import com.yrootlab.onmaru.observability.TelemetrySink;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Clock;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcOdiiSchedulerRunTests {
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432).withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test").withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
    private static DataSource database;

    @BeforeAll
    static void startDatabase() throws Exception {
        POSTGRES.start();
        database = new DriverManagerDataSource("jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/onmaru_test", "onmaru_test", "onmaru_test");
        try (var connection = database.getConnection()) {
            PostgresTestDatabase.reset(connection);
        }
        Flyway.configure().dataSource(database).locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
    }

    @AfterAll
    static void stopDatabase() { POSTGRES.stop(); }

    @Test
    void recordsMissingComponentSkipWhenDatabaseIsAvailable() throws Exception {
        var beans = new StaticListableBeanFactory(Map.of("database", database));
        String dataset = "odii-missing-" + UUID.randomUUID();
        var scheduler = scheduler(beans, dataset);
        runAndAssertTerminal(scheduler, "SKIPPED", "MISSING_COMPONENT");
        assertRun(dataset, "ABANDONED", "SKIPPED", "DEPENDENCY_CHECK", "MISSING_COMPONENT");
        assertCounts(dataset, 0, 0, 0, 0, 0);
    }

    @Test
    void recordsProviderFailureWithoutSavingTheProviderMessage() throws Exception {
        String dataset = "odii-provider-" + UUID.randomUUID();
        var store = new JdbcAudioRevisionStore(database);
        var service = new OdiiRevisionSyncService(store, (language, keyword, page) -> {
            throw new OdiiSourceException("secret-provider-key");
        }, new OdiiSourceMapper(), Clock.systemUTC());
        var beans = new StaticListableBeanFactory(Map.of("database", database, "store", store, "service", service));
        runAndAssertTerminal(scheduler(beans, dataset), "FAILED", "SOURCE_FAILED");
        assertRun(dataset, "FAILED", "FAILED", "FETCH", "SOURCE_FAILED");
        assertCounts(dataset, 0, 0, 0, 0, 0);
    }

    @Test
    void recordsSuccessfulPublicationAndStageCounts() throws Exception {
        String dataset = "odii-success-" + UUID.randomUUID();
        runAndAssertTerminal(successfulScheduler(dataset, source("ko")), "COMPLETED", "phase=PUBLISH");
        assertRun(dataset, "SUCCEEDED", "COMPLETED", null, null);
        try (var connection = database.getConnection(); var statement = connection.prepareStatement(
                "SELECT counts->>'staged', revision_id, lease_generation FROM onmaru.operations_sync_runs WHERE dataset = ?")) {
            statement.setString(1, dataset);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                // One source story persists one spot row and one story row.
                assertThat(rows.getString(1)).isEqualTo("2");
                assertThat(rows.getObject(2)).isNotNull();
                assertThat(rows.getInt(3)).isEqualTo(1);
            }
        }
    }

    @Test
    void productionProfilePublishesHttpFixtureWithCompleteDurableAndLogCounts() throws Exception {
        String dataset = "odii-production-" + UUID.randomUUID();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/odii", exchange -> {
            try {
                byte[] body = sourceFixture().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("production");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("odii-fixture", Map.of(
                    "onmaru.audio.dataset", dataset,
                    "onmaru.odii.sync.base-uri", "http://127.0.0.1:" + server.getAddress().getPort() + "/odii",
                    "onmaru.odii.sync.languages", "ko",
                    "onmaru.odii.client.retry-count", "0")));
            context.registerBean(DataSource.class, () -> database);
            context.registerBean(Clock.class, Clock::systemUTC);
            context.registerBean(SecretProvider.class, () -> name ->
                    new SecretBundle(name, "secret-provider-key", Optional.empty()));
            context.registerBean(TelemetrySink.class, () -> ignored -> { });
            context.register(AudioPersistenceConfiguration.class, OdiiClientConfiguration.class,
                    OdiiSyncSchedulingAdapter.class);
            context.refresh();
            assertThat(context.getBean(AudioRevisionStore.class)).isInstanceOf(JdbcAudioRevisionStore.class);
            String terminal = runAndAssertTerminal(context.getBean(OdiiSyncSchedulingAdapter.class)::initialSync,
                    "COMPLETED", "phase=PUBLISH");
            assertThat(terminal).contains("fetched=3", "mapped=1", "staged=2", "published=2", "tombstones=0");
            assertRun(dataset, "SUCCEEDED", "COMPLETED", null, null);
            assertCounts(dataset, 3, 1, 2, 2, 0);
            try (var connection = database.getConnection(); var statement = connection.prepareStatement("""
                    SELECT COUNT(*) FROM onmaru.audio_story_versions story
                    JOIN onmaru.catalog_active_datasets active ON story.revision_id = active.revision_id
                    JOIN onmaru.operations_sync_runs run ON run.revision_id = active.revision_id
                    WHERE run.dataset = ? AND active.dataset = ? AND story.status = 'ACTIVE'
                    """)) {
                statement.setString(1, dataset);
                statement.setString(2, dataset);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(1);
                }
            }
        } finally { server.stop(0); }
    }

    @Test
    void recordsLeaseContentionAsSkip() throws Exception {
        String dataset = "odii-held-" + UUID.randomUUID();
        execute("INSERT INTO onmaru.operations_sync_leases VALUES ('" + dataset + "', 'another-owner', 1, now() + interval '1 hour')");
        runAndAssertTerminal(successfulScheduler(dataset, source("ko")), "SKIPPED", "LEASE_NOT_ACQUIRED");
        assertRun(dataset, "ABANDONED", "SKIPPED", "LEASE", "LEASE_NOT_ACQUIRED");
        assertCounts(dataset, 0, 0, 0, 0, 0);
    }

    @Test
    void distinguishesLeaseDatabaseErrorFromLeaseContention() throws Exception {
        String dataset = "odii-lease-db-" + UUID.randomUUID();
        installFailure("operations_sync_leases", "INSERT OR UPDATE");
        try {
            runAndAssertTerminal(successfulScheduler(dataset, source("ko")), "FAILED", "LEASE_DB_ERROR");
            assertRun(dataset, "FAILED", "FAILED", "LEASE", "LEASE_DB_ERROR");
        } finally { removeFailure("operations_sync_leases"); }
    }

    @Test
    void distinguishesMappingFailureFromSourceFailure() throws Exception {
        String dataset = "odii-map-" + UUID.randomUUID();
        runAndAssertTerminal(successfulScheduler(dataset, source("unsupported")), "FAILED", "MAPPING_FAILED");
        assertRun(dataset, "FAILED", "FAILED", "MAP", "MAPPING_FAILED");
        assertCounts(dataset, 1, 0, 0, 0, 0);
    }

    @Test
    void recordsStageOpenDatabaseFailure() throws Exception {
        String dataset = "odii-stage-open-" + UUID.randomUUID();
        var scheduler = successfulScheduler(dataset, source("ko"));
        installFailure("audio_revision_stages", "INSERT");
        try {
            runAndAssertTerminal(scheduler, "FAILED", "STAGE_OPEN_FAILED");
            assertRun(dataset, "FAILED", "FAILED", "STAGE", "STAGE_OPEN_FAILED");
        } finally { removeFailure("audio_revision_stages"); }
    }

    @Test
    void recordsStagingDatabaseFailure() throws Exception {
        String dataset = "odii-stage-" + UUID.randomUUID();
        installFailure("audio_story_versions", "INSERT");
        try {
            runAndAssertTerminal(successfulScheduler(dataset, source("ko")), "FAILED", "STAGING_FAILED");
            assertRun(dataset, "FAILED", "FAILED", "STAGE", "STAGING_FAILED");
            assertCounts(dataset, 1, 1, 0, 0, 0);
        } finally { removeFailure("audio_story_versions"); }
    }

    @Test
    void preservesOriginalStageFailureWhenFailureCleanupAlsoFails() throws Exception {
        String dataset = "odii-compound-stage-" + UUID.randomUUID();
        installFailure("audio_story_versions", "INSERT");
        installFailure("audio_revision_stages", "UPDATE");
        try {
            runAndAssertTerminal(successfulScheduler(dataset, source("ko")), "FAILED", "STAGING_FAILED");
            assertRun(dataset, "FAILED", "FAILED", "STAGE", "STAGING_FAILED");
            assertCounts(dataset, 1, 1, 0, 0, 0);
        } finally {
            removeFailure("audio_story_versions");
            removeFailure("audio_revision_stages");
        }
    }

    @Test
    void recordsPublishDatabaseFailure() throws Exception {
        String dataset = "odii-publish-" + UUID.randomUUID();
        installFailure("catalog_active_datasets", "UPDATE");
        try {
            runAndAssertTerminal(successfulScheduler(dataset, source("ko")), "FAILED", "PUBLISH_FAILED");
            assertRun(dataset, "FAILED", "FAILED", "PUBLISH", "PUBLISH_FAILED");
            assertCounts(dataset, 1, 1, 2, 0, 0);
        } finally { removeFailure("catalog_active_datasets"); }
    }

    @Test
    void preservesCompletedStageTombstoneCountWhenPublicationFails() throws Exception {
        String dataset = "odii-publish-tombstone-" + UUID.randomUUID();
        var replacement = new OdiiSourceStory("t-382", "tl-382", "s-383", "sl-383", "한옥 이야기", "한옥 산책",
                "한옥의 역사", "https://example.test/audio.mp3", null, "60", "127.0", "37.5", "ko",
                "20260928000000", "20260928000000");
        runAndAssertTerminal(successfulScheduler(dataset, source("ko")), "COMPLETED", "phase=PUBLISH");
        // First absence keeps the original story active; the second marks it deleted.
        runAndAssertTerminal(successfulScheduler(dataset, replacement), "COMPLETED", "phase=PUBLISH");
        installFailure("catalog_active_datasets", "UPDATE");
        try {
            String terminal = runAndAssertTerminal(successfulScheduler(dataset, replacement), "FAILED", "PUBLISH_FAILED");
            try (var connection = database.getConnection(); var statement = connection.prepareStatement("""
                    SELECT stage.tombstone_count, run.counts->>'tombstones', run.failure_phase,
                           run.error_code, run.status::text, run.counts->>'staged', run.counts->>'published'
                    FROM onmaru.operations_sync_runs run
                    JOIN onmaru.audio_revision_stages stage ON stage.revision_id = run.revision_id
                    WHERE run.dataset = ? ORDER BY run.started_at DESC LIMIT 1
                    """)) {
                statement.setString(1, dataset);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).as("committed stage completion found one deleted story").isEqualTo(1);
                    assertThat(rows.getString(2)).as("durable run must preserve known tombstones after publish failure")
                            .isEqualTo("1");
                    assertThat(rows.getString(3)).isEqualTo("PUBLISH");
                    assertThat(rows.getString(4)).isEqualTo("PUBLISH_FAILED");
                    assertThat(rows.getString(5)).isEqualTo("FAILED");
                    assertThat(rows.getString(6)).isEqualTo("3");
                    assertThat(rows.getString(7)).isEqualTo("0");
                }
            }
            assertThat(terminal).contains("phase=PUBLISH", "staged=3", "published=0", "tombstones=1");
        } finally { removeFailure("catalog_active_datasets"); }
    }

    private static OdiiSyncSchedulingAdapter successfulScheduler(String dataset, OdiiSourceStory story) {
        var store = new JdbcAudioRevisionStore(database);
        var service = new OdiiRevisionSyncService(store,
                (language, keyword, page) -> new OdiiSourcePage(List.of(story), true),
                new OdiiSourceMapper(), Clock.systemUTC());
        return scheduler(new StaticListableBeanFactory(Map.of("database", database, "store", store, "service", service)), dataset);
    }

    private static OdiiSourceStory source(String language) {
        return new OdiiSourceStory("t-382", "tl-382", "s-382", "sl-382", "한옥 이야기", "한옥 산책",
                "한옥의 역사", "https://example.test/audio.mp3", null, "60", "127.0", "37.5", language,
                "20260928000000", "20260928000000");
    }

    private static void installFailure(String table, String action) throws Exception {
        execute("CREATE OR REPLACE FUNCTION onmaru.fail_odii_scheduler_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'secret-provider-key'; END $$");
        execute("CREATE TRIGGER fail_odii_scheduler_test BEFORE " + action + " ON onmaru." + table
                + " FOR EACH ROW EXECUTE FUNCTION onmaru.fail_odii_scheduler_test()");
    }

    private static void removeFailure(String table) throws Exception {
        execute("DROP TRIGGER fail_odii_scheduler_test ON onmaru." + table);
    }

    private static void execute(String sql) throws Exception {
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static OdiiSyncSchedulingAdapter scheduler(StaticListableBeanFactory beans, String dataset) {
        return new OdiiSyncSchedulingAdapter(beans.getBeanProvider(OdiiRevisionSyncService.class),
                beans.getBeanProvider(AudioRevisionStore.class), beans.getBeanProvider(DataSource.class),
                dataset, List.of("ko"));
    }

    private static String runAndAssertTerminal(OdiiSyncSchedulingAdapter scheduler, String status, String reason) {
        return runAndAssertTerminal(() -> scheduler.runSync("application-ready"), status, reason);
    }

    private static String runAndAssertTerminal(Runnable trigger, String status, String reason) {
        var logger = (Logger) LoggerFactory.getLogger(OdiiSyncSchedulingAdapter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            trigger.run();
            var terminals = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("ODII_SYNC_TERMINAL")).toList();
            assertThat(terminals).singleElement()
                    .satisfies(message -> assertThat(message).contains("status=" + status, reason)
                            .doesNotContain("secret-provider-key", "jdbc:", "serviceKey=", "Authorization"));
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain("secret-provider-key", "jdbc:", "serviceKey=", "Authorization"));
            return terminals.getFirst();
        } finally { logger.detachAppender(appender); }
    }

    private static void assertCounts(String dataset, long fetched, long mapped, long staged, long published,
                                     long tombstones) throws Exception {
        try (var connection = database.getConnection(); var statement = connection.prepareStatement("""
                SELECT counts->>'fetched', counts->>'mapped', counts->>'staged', counts->>'published',
                       counts->>'tombstones' FROM onmaru.operations_sync_runs WHERE dataset = ?
                """)) {
            statement.setString(1, dataset);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(List.of(Optional.ofNullable(rows.getString(1)).orElse("missing"),
                        Optional.ofNullable(rows.getString(2)).orElse("missing"), rows.getString(3),
                        rows.getString(4), rows.getString(5)))
                        .containsExactly("" + fetched, "" + mapped, "" + staged, "" + published, "" + tombstones);
            }
        }
    }

    private static String sourceFixture() {
        String included = """
                {"tid":"89","tlid":"300","stid":"562","stlid":"1204", "langCode":"ko",
                 "title":"한옥마을","audioTitle":"한옥 산책","script":"한옥의 역사",
                 "audioUrl":"https://example.test/audio.mp3","imageUrl":"","playTime":"60",
                 "mapX":"127.0","mapY":"37.5","createdtime":"20260928000000",
                 "modifiedtime":"20260928000000"}
                """;
        String excluded = included.replace("1204", "1205").replace("한옥마을", "호텔")
                .replace("한옥 산책", "쇼핑").replace("한옥의 역사", "숙박");
        return """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},
                 "body":{"items":{"item":[%s,%s,%s]},"numOfRows":100,"pageNo":1,"totalCount":3}}}
                """.formatted(included, included, excluded);
    }

    private static void assertRun(String dataset, String status, String lifecycle, String phase, String reason)
            throws Exception {
        try (var connection = database.getConnection();
             var statement = connection.prepareStatement("SELECT to_jsonb(run)::text FROM onmaru.operations_sync_runs run WHERE dataset = ?")) {
            statement.setString(1, dataset);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("scheduler must persist one terminal run").isTrue();
                String json = rows.getString(1);
                assertThat(json).contains("\"status\": \"" + status + "\"", "\"lifecycle_status\": \"" + lifecycle + "\"",
                        "\"failure_phase\": " + (phase == null ? "null" : "\"" + phase + "\""),
                        "\"error_code\": " + (reason == null ? "null" : "\"" + reason + "\""),
                        "\"trigger_source\": \"application-ready\"").doesNotContain("secret-provider-key", "\"finished_at\": null");
                assertThat(rows.next()).isFalse();
            }
        }
    }
}

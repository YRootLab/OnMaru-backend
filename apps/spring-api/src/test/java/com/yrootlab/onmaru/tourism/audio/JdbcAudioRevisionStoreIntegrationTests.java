package com.yrootlab.onmaru.tourism.audio;

import com.yrootlab.onmaru.audio.sync.AudioRevisionStore;
import com.yrootlab.onmaru.audio.sync.OdiiPageSource;
import com.yrootlab.onmaru.audio.sync.OdiiRevisionSyncService;
import com.yrootlab.onmaru.audio.sync.OdiiSourceException;
import com.yrootlab.onmaru.audio.sync.OdiiSourceMapper;
import com.yrootlab.onmaru.audio.sync.OdiiSourcePage;
import com.yrootlab.onmaru.audio.sync.OdiiSourceStory;
import com.yrootlab.onmaru.audio.sync.OdiiSyncCommand;
import com.yrootlab.onmaru.audio.sync.OdiiSyncStatus;
import com.yrootlab.onmaru.audio.placelink.AudioPlaceLinkCandidate;
import com.yrootlab.onmaru.audio.placelink.AudioPlaceLinkMatchMethod;
import com.yrootlab.onmaru.audio.placelink.AudioPlaceLinkReviewStatus;
import com.yrootlab.onmaru.persistence.audio.JdbcAudioPlaceLinkStore;
import com.yrootlab.onmaru.persistence.audio.AudioPersistenceConfiguration;
import com.yrootlab.onmaru.audio.sync.InMemoryAudioRevisionStore;
import com.yrootlab.onmaru.config.secrets.SecretBundle;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.observability.InMemoryTelemetrySink;
import com.yrootlab.onmaru.web.audio.OdiiStoryConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.sync.SyncRunLease;
import com.yrootlab.onmaru.testing.postgres.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

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
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAudioRevisionStoreIntegrationTests {

    private static final int POSTGRES_PORT = 5432;
    private static final String DATABASE = "onmaru_test";
    private static final String USERNAME = "onmaru_test";
    private static final String PASSWORD = "onmaru_test";
    private static final String DATASET = "odii-audio";
    private static final Instant NOW = Instant.parse("2026-09-16T03:00:00Z");

    private static final GenericContainer<?> postgres = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(POSTGRES_PORT)
            .withEnv("POSTGRES_DB", DATABASE)
            .withEnv("POSTGRES_USER", USERNAME)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres.start();
        resetAndMigrate();
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @Test
    void persistsPublishedRevisionAcrossRestartAndKeepsLkgOnFailureAndStaleWriter() throws Exception {
        UUID baseRevision = UUID.randomUUID();
        seedPublicationControl(baseRevision);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var mapper = new OdiiSourceMapper();
        AudioRevisionStore firstStore = new JdbcAudioRevisionStore(dataSource());
        var successful = new OdiiRevisionSyncService(
                firstStore,
                (language, keyword, page) -> new OdiiSourcePage(List.of(source()), true),
                mapper,
                clock);

        var published = successful.sync(command(baseRevision));

        assertThat(published.status()).isEqualTo(OdiiSyncStatus.PUBLISHED);
        AudioRevisionStore restartedStore = new JdbcAudioRevisionStore(dataSource());
        var restarted = restartedStore.activePublishedRevision(DATASET);
        assertThat(restarted.revisionId()).isEqualTo(published.stagedRevisionId());
        assertThat(restarted.snapshot().stories()).singleElement().satisfies(story -> {
            assertThat(story.title()).isEqualTo("한옥 골목 이야기");
            assertThat(story.subtitleLines()).extracting(line -> line.text())
                    .containsExactly("첫 문장입니다.", "두 번째 문장입니다.");
        });

        UUID lkgRevision = restarted.revisionId();
        OdiiPageSource lastPageFailure = (language, keyword, page) -> {
            if (page == 1) {
                return new OdiiSourcePage(List.of(source()), false);
            }
            throw new OdiiSourceException("last page failed");
        };
        var failed = new OdiiRevisionSyncService(
                restartedStore,
                lastPageFailure,
                mapper,
                clock).sync(command(lkgRevision));
        var stale = successful.sync(command(UUID.randomUUID()));

        assertThat(failed.status()).isEqualTo(OdiiSyncStatus.SOURCE_FAILED);
        assertThat(stale.status()).isEqualTo(OdiiSyncStatus.ACTIVE_REVISION_CHANGED);
        assertThat(new JdbcAudioRevisionStore(dataSource()).activeRevision(DATASET))
                .isEqualTo(lkgRevision);
    }

    @Test
    void concurrentApprovalHasOneWinnerAndFailedTransitionRollsBack() throws Exception {
        UUID spotId = UUID.randomUUID();
        UUID firstPlaceId = UUID.randomUUID();
        UUID secondPlaceId = UUID.randomUUID();
        UUID failingPlaceId = UUID.randomUUID();
        seedLinkIdentities(spotId, firstPlaceId, secondPlaceId, failingPlaceId);
        String publicSpotId = "odii-spot-" + spotId;
        var telemetry = new InMemoryTelemetrySink();
        var firstStore = new JdbcAudioPlaceLinkStore(dataSource(), telemetry);
        var secondStore = new JdbcAudioPlaceLinkStore(dataSource(), telemetry);
        firstStore.saveIfAbsent(candidate(publicSpotId, firstPlaceId));
        firstStore.saveIfAbsent(candidate(publicSpotId, secondPlaceId));

        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> approve(start, firstStore, publicSpotId, firstPlaceId));
            var second = executor.submit(() -> approve(start, secondStore, publicSpotId, secondPlaceId));
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(firstStore.findBySpotId(publicSpotId))
                .filteredOn(link -> link.reviewStatus() == AudioPlaceLinkReviewStatus.APPROVED)
                .hasSize(1);
        assertThat(telemetry.events())
                .filteredOn(event -> event.name().startsWith("odii.place_link.approval."))
                .extracting(event -> event.attributes().get("status"))
                .contains("APPROVED", "CONFLICT");

        firstStore.saveIfAbsent(candidate(publicSpotId, failingPlaceId));
        UUID approvedBeforeFailure = UUID.fromString(firstStore.findBySpotId(publicSpotId).stream()
                .filter(link -> link.reviewStatus() == AudioPlaceLinkReviewStatus.APPROVED)
                .findFirst()
                .orElseThrow()
                .placeId());
        installApprovalFailureTrigger(failingPlaceId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> firstStore.approveExclusive(
                        publicSpotId,
                        failingPlaceId.toString(),
                        NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(firstStore.findBySpotId(publicSpotId))
                .filteredOn(link -> link.reviewStatus() == AudioPlaceLinkReviewStatus.APPROVED)
                .singleElement()
                .extracting(link -> UUID.fromString(link.placeId()))
                .isEqualTo(approvedBeforeFailure);
        assertThat(telemetry.events())
                .filteredOn(event -> event.name().equals("odii.place_link.approval.failed"))
                .extracting(event -> event.attributes().get("status"))
                .contains("ROLLBACK");
    }

    @Test
    void productionProfileSelectsJdbcRevisionStoreAndNeverCreatesInMemoryStore() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("production");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "odii-profile-test",
                    Map.of("onmaru.audio.dataset", "odii-profile-test")));
            context.registerBean(DataSource.class, JdbcAudioRevisionStoreIntegrationTests::dataSource);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(Clock.class, Clock::systemUTC);
            context.registerBean(SecretProvider.class, () -> name ->
                    new SecretBundle(name, "profile-test-secret-value-32-bytes-min", Optional.empty()));
            context.register(AudioPersistenceConfiguration.class, OdiiStoryConfiguration.class);
            context.refresh();

            assertThat(context.getBean(AudioRevisionStore.class))
                    .isInstanceOf(JdbcAudioRevisionStore.class)
                    .isNotInstanceOf(InMemoryAudioRevisionStore.class);
        }
    }

    @Test
    void productionProfileSelectsJdbcRevisionStoreRegardlessOfConfigurationRegistrationOrder() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("production");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "odii-profile-order-test",
                    Map.of("onmaru.audio.dataset", "odii-profile-order-test")));
            context.registerBean(DataSource.class, JdbcAudioRevisionStoreIntegrationTests::dataSource);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(Clock.class, Clock::systemUTC);
            context.registerBean(SecretProvider.class, () -> name ->
                    new SecretBundle(name, "profile-test-secret-value-32-bytes-min", Optional.empty()));
            context.register(OdiiStoryConfiguration.class, AudioPersistenceConfiguration.class);
            context.refresh();

            assertThat(context.getBean(AudioRevisionStore.class))
                    .isInstanceOf(JdbcAudioRevisionStore.class)
                    .isNotInstanceOf(InMemoryAudioRevisionStore.class);
        }
    }

    @Test
    void productionProfileSelectsJdbcRevisionStoreWhenDataSourceConfigurationIsProcessedLater() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("production");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "odii-late-datasource-test",
                    Map.of("onmaru.audio.dataset", "odii-late-datasource-test")));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(Clock.class, Clock::systemUTC);
            context.registerBean(SecretProvider.class, () -> name ->
                    new SecretBundle(name, "profile-test-secret-value-32-bytes-min", Optional.empty()));
            context.register(
                    AudioPersistenceConfiguration.class,
                    OdiiStoryConfiguration.class,
                    LateDataSourceConfiguration.class);
            context.refresh();

            assertThat(context.getBean(AudioRevisionStore.class))
                    .isInstanceOf(JdbcAudioRevisionStore.class)
                    .isNotInstanceOf(InMemoryAudioRevisionStore.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LateDataSourceConfiguration {

        @Bean
        DataSource lateDataSource() {
            return JdbcAudioRevisionStoreIntegrationTests.dataSource();
        }
    }

    private boolean approve(
            CountDownLatch start,
            JdbcAudioPlaceLinkStore store,
            String spotId,
            UUID placeId
    ) {
        try {
            start.await();
            store.approveExclusive(spotId, placeId.toString(), NOW);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private AudioPlaceLinkCandidate candidate(String spotId, UUID placeId) {
        return new AudioPlaceLinkCandidate(
                spotId,
                placeId.toString(),
                AudioPlaceLinkMatchMethod.MANUAL_REFERENCE,
                new BigDecimal("0.95"),
                AudioPlaceLinkReviewStatus.PENDING,
                null);
    }

    private static void seedLinkIdentities(UUID spotId, UUID... placeIds) throws Exception {
        try (var connection = dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.audio_odii_spots (
                        id, provider, tid, tlid, lang_code, created_at
                    ) VALUES ('%s', 'KTO_ODII', 'link-tid', 'link-tlid', 'ko', CURRENT_TIMESTAMP)
                    """.formatted(spotId));
            for (UUID placeId : placeIds) {
                statement.execute("""
                        INSERT INTO onmaru.catalog_place_identity (id, created_at)
                        VALUES ('%s', CURRENT_TIMESTAMP)
                        """.formatted(placeId));
            }
        }
    }

    private static void installApprovalFailureTrigger(UUID placeId) throws Exception {
        try (var connection = dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE OR REPLACE FUNCTION onmaru.fail_selected_audio_approval()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.review_status = 'APPROVED' AND NEW.place_id = '%s'::uuid THEN
                            RAISE EXCEPTION 'forced approval failure';
                        END IF;
                        RETURN NEW;
                    END
                    $$
                    """.formatted(placeId));
            statement.execute("""
                    CREATE TRIGGER audio_place_link_forced_failure
                    BEFORE UPDATE ON onmaru.audio_place_odii_links
                    FOR EACH ROW EXECUTE FUNCTION onmaru.fail_selected_audio_approval()
                    """);
        }
    }

    private OdiiSyncCommand command(UUID expectedRevision) {
        return new OdiiSyncCommand(
                DATASET,
                new SyncRunLease(UUID.randomUUID(), DATASET, "worker-a", 1),
                expectedRevision,
                List.of("ko"),
                false);
    }

    private OdiiSourceStory source() {
        return new OdiiSourceStory(
                "89",
                "300",
                "562",
                "1204",
                "전주 한옥마을",
                "한옥 골목 이야기",
                "첫 문장입니다.\n두 번째 문장입니다.",
                "https://cdn.onmaru.example/story.mp3",
                "https://cdn.onmaru.example/story.jpg",
                "185",
                "127.152948",
                "35.817632",
                "ko",
                "20150619173503",
                "20250609074606");
    }

    private static void seedPublicationControl(UUID baseRevision) throws Exception {
        try (var connection = dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.catalog_dataset_revisions (
                        id, dataset, status, source_observed_at, fetched_at, published_at
                    ) VALUES (
                        '%s', 'odii-audio', 'PUBLISHED', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    """.formatted(baseRevision));
            statement.execute("""
                    INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                    VALUES ('odii-audio', '%s', CURRENT_TIMESTAMP)
                    """.formatted(baseRevision));
            statement.execute("""
                    INSERT INTO onmaru.operations_sync_leases (dataset, owner_token, generation, lease_until)
                    VALUES ('odii-audio', 'worker-a', 1, CURRENT_TIMESTAMP + INTERVAL '1 day')
                    """);
            statement.execute("""
                    INSERT INTO onmaru.operations_sync_watermarks (
                        dataset, source_modified_at, external_id, last_full_success_at,
                        last_success_at, revision_id
                    ) VALUES (
                        'odii-audio', '2025-01-01T00:00:00Z', 'base', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, '%s'
                    )
                    """.formatted(baseRevision));
        }
    }

    private static void resetAndMigrate() throws Exception {
        try (var connection = dataSource().getConnection()) {
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

    private static DataSource dataSource() {
        return new DriverManagerDataSource(jdbcUrl(), USERNAME, PASSWORD);
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
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { DriverManager.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() { return DriverManager.getLoginTimeout(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}

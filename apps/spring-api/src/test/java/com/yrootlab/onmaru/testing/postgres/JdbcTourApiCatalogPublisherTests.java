package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStore;
import com.yrootlab.onmaru.catalog.application.query.hanok.InMemoryHanokListStore;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceStore;
import com.yrootlab.onmaru.catalog.application.query.spatial.InMemoryMapPlaceStore;
import com.yrootlab.onmaru.catalog.editorial.MonthlyHanokEditionService;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokIngestionService;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokQueryService;
import com.yrootlab.onmaru.config.secrets.FakeSecretProvider;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.journey.saved.place.InMemorySavedPlaceStore;
import com.yrootlab.onmaru.persistence.catalog.CatalogSnapshotPersistenceConfiguration;
import com.yrootlab.onmaru.persistence.catalog.JdbcCatalogPlaceSnapshotStore;
import com.yrootlab.onmaru.persistence.catalog.JdbcPlaceDetailStore;
import com.yrootlab.onmaru.persistence.catalog.JdbcTourApiCatalogPublisher;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTourApiCatalogPublisherTests {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private DataSource dataSource;

    @BeforeAll
    static void start() { POSTGRES.start(); }

    @AfterAll
    static void stop() { POSTGRES.stop(); }

    @BeforeEach
    void migrate() throws Exception {
        try (var connection = DriverManager.getConnection(url(), "onmaru_test", "onmaru_test")) {
            PostgresTestDatabase.reset(connection);
        }
        Flyway.configure().dataSource(url(), "onmaru_test", "onmaru_test")
                .locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        dataSource = new DriverManagerDataSource(url(), "onmaru_test", "onmaru_test");
    }

    @Test
    void storesEveryRawRowButPublishesOnlyQualifiedPlaces() throws Exception {
        var publisher = new JdbcTourApiCatalogPublisher(dataSource);
        var fetchedAt = Instant.parse("2026-09-27T03:00:00Z");
        var session = publisher.start(fetchedAt);
        var page = publisher.stagePage(session, List.of(
                row("2001", "북촌 한옥", "HANOK"),
                row("2002", "전주 남부시장", "TRADITIONAL_MARKET"),
                row("2003", "한옥이라는 단어가 들어간 일반 서점", null),
                invalidCoordinateRow("2004", "좌표가 깨진 공공데이터")
        ));
        var result = publisher.complete(session, page.rawCount(), page.rawCount(),
                page.publishedCount(), page.quarantinedCount(), page.skippedCount(), fetchedAt);

        assertThat(result.publishedCount()).isEqualTo(2);
        assertThat(result.quarantinedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(queryCount("onmaru.catalog_kto_korean_content_versions")).isEqualTo(4);
        assertThat(queryCount("onmaru.operations_sync_quarantine")).isZero();
        assertThat(queryCount("onmaru.catalog_kto_korean_content_versions WHERE ldong_regn_cd = '11' AND ldong_signgu_cd = '110'"))
                .isEqualTo(4);
        var hanokSnapshot = new JdbcCatalogPlaceSnapshotStore(dataSource).findPublishedHanokSnapshot();
        assertThat(hanokSnapshot)
                .extracting(place -> place.name())
                .containsExactly("북촌 한옥");
        assertThat(hanokSnapshot.getFirst().address()).isEqualTo("서울 종로구");
        assertThat(hanokSnapshot.getFirst().coordinates().lat()).isEqualTo(37.58);
        assertThat(hanokSnapshot.getFirst().coordinates().lng()).isEqualTo(126.98);

        var detailStore = new JdbcPlaceDetailStore(dataSource);
        var placeId = hanokSnapshot.getFirst().placeId();
        var detail = detailStore.findByPlaceId(placeId).orElseThrow();
        var source = detailStore.findTourApiReference(placeId).orElseThrow();
        assertThat(detail.name()).isEqualTo("북촌 한옥");
        assertThat(detail.category()).isEqualTo("HANOK");
        assertThat(detail.address()).isEqualTo("서울 종로구");
        assertThat(source.contentId()).isEqualTo("2001");
        assertThat(source.contentTypeId()).isEqualTo("12");
    }

    @Test
    void reusesActiveRevisionWhenCompletedSnapshotIsUnchanged() throws Exception {
        var publisher = new JdbcTourApiCatalogPublisher(dataSource);
        var records = List.of(
                row("2001", "북촌 한옥", "HANOK"),
                row("2002", "전주 남부시장", "TRADITIONAL_MARKET"),
                row("2003", "서비스 범위 밖 일반 관광지", null),
                invalidCoordinateRow("2004", "좌표 오류"));

        var firstSession = publisher.start(Instant.parse("2026-09-27T03:00:00Z"));
        var firstPage = publisher.stagePage(firstSession, records);
        var first = publisher.complete(firstSession, firstPage.rawCount(), firstPage.rawCount(),
                firstPage.publishedCount(), firstPage.quarantinedCount(), firstPage.skippedCount(),
                Instant.parse("2026-09-27T03:01:00Z"));

        var secondSession = publisher.start(Instant.parse("2026-09-28T03:00:00Z"));
        var secondPage = publisher.stagePage(secondSession, records);
        var second = publisher.complete(secondSession, secondPage.rawCount(), secondPage.rawCount(),
                secondPage.publishedCount(), secondPage.quarantinedCount(), secondPage.skippedCount(),
                Instant.parse("2026-09-28T03:01:00Z"));

        assertThat(second.revisionId()).isEqualTo(first.revisionId());
        assertThat(queryCount("onmaru.catalog_dataset_revisions WHERE dataset = 'kto-korean-tour'"))
                .isEqualTo(1);
        assertThat(queryCount("onmaru.catalog_kto_korean_content_versions")).isEqualTo(4);
        assertThat(queryCount("onmaru.catalog_place_versions")).isEqualTo(2);
        assertThat(queryCount("onmaru.operations_sync_quarantine")).isZero();
    }

    @Test
    void replacesChangedSnapshotWithoutRetainingPreviousPublishedRevision() throws Exception {
        var publisher = new JdbcTourApiCatalogPublisher(dataSource);
        var firstSession = publisher.start(Instant.parse("2026-09-27T03:00:00Z"));
        var firstPage = publisher.stagePage(firstSession, List.of(row("2001", "북촌 한옥", "HANOK")));
        publisher.complete(firstSession, firstPage.rawCount(), firstPage.rawCount(),
                firstPage.publishedCount(), firstPage.quarantinedCount(), firstPage.skippedCount(),
                Instant.parse("2026-09-27T03:01:00Z"));

        var secondSession = publisher.start(Instant.parse("2026-09-30T03:00:00Z"));
        var secondPage = publisher.stagePage(secondSession, List.of(
                row("2001", "북촌 한옥", "HANOK"),
                row("2002", "전주 남부시장", "TRADITIONAL_MARKET")));
        publisher.complete(secondSession, secondPage.rawCount(), secondPage.rawCount(),
                secondPage.publishedCount(), secondPage.quarantinedCount(), secondPage.skippedCount(),
                Instant.parse("2026-09-30T03:01:00Z"));

        assertThat(queryCount("onmaru.catalog_dataset_revisions WHERE dataset = 'kto-korean-tour'"))
                .isEqualTo(1);
        assertThat(queryCount("onmaru.catalog_kto_korean_content_versions")).isEqualTo(2);
    }

    @Test
    void failedSnapshotKeepsOnlyRunMetadataAndDeletesStagedRowsImmediately() throws Exception {
        var publisher = new JdbcTourApiCatalogPublisher(dataSource);
        var session = publisher.start(Instant.parse("2026-09-27T03:00:00Z"));
        publisher.stagePage(session, List.of(
                row("2001", "북촌 한옥", "HANOK"),
                invalidCoordinateRow("2002", "좌표 오류")));

        publisher.fail(session, "TOURAPI_SYNC_FAILED", Instant.parse("2026-09-27T03:01:00Z"));

        assertThat(queryCount("onmaru.catalog_dataset_revisions WHERE dataset = 'kto-korean-tour'"))
                .isZero();
        assertThat(queryCount("onmaru.catalog_kto_korean_content_versions")).isZero();
        assertThat(queryCount("onmaru.catalog_place_versions")).isZero();
        assertThat(queryCount("onmaru.operations_sync_quarantine")).isZero();
        assertThat(queryCount("onmaru.operations_sync_runs WHERE status = 'FAILED' AND revision_id IS NULL"))
                .isEqualTo(1);
    }

    @Test
    void nextFullSyncBecomesDueOnlyAfterConfiguredMinimumInterval() throws Exception {
        var publisher = new JdbcTourApiCatalogPublisher(dataSource);
        var completedAt = Instant.parse("2026-09-27T03:01:00Z");
        var session = publisher.start(Instant.parse("2026-09-27T03:00:00Z"));
        var page = publisher.stagePage(session, List.of(row("2001", "북촌 한옥", "HANOK")));
        publisher.complete(session, page.rawCount(), page.rawCount(),
                page.publishedCount(), page.quarantinedCount(), page.skippedCount(), completedAt);

        assertThat(publisher.isSyncDue(completedAt.plus(Duration.ofHours(71)), Duration.ofHours(72)))
                .isFalse();
        assertThat(publisher.isSyncDue(completedAt.plus(Duration.ofHours(72)), Duration.ofHours(72)))
                .isTrue();
    }

    @Test
    void productionUsesNeonSnapshotsInsteadOfHardcodedStores() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("production");
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(InMemorySavedPlaceStore.class, InMemorySavedPlaceStore::new);
            context.registerBean(SecretProvider.class, FakeSecretProvider::new);
            context.registerBean(Clock.class, Clock::systemUTC);
            context.register(
                    CatalogSnapshotPersistenceConfiguration.class,
                    Class.forName("com.yrootlab.onmaru.web.map.place.MapPlaceConfiguration"),
                    Class.forName("com.yrootlab.onmaru.web.hanok.list.HanokListConfiguration"),
                    Class.forName("com.yrootlab.onmaru.web.editorial.MonthlyHanokEditionConfiguration"),
                    Class.forName("com.yrootlab.onmaru.web.screenhanok.ScreenHanokConfiguration"));
            context.refresh();

            assertThat(context.getBean(MapPlaceStore.class)).isNotInstanceOf(InMemoryMapPlaceStore.class);
            assertThat(context.getBean(HanokListStore.class)).isNotInstanceOf(InMemoryHanokListStore.class);
            assertThat(context.getBeansOfType(InMemoryMapPlaceStore.class)).isEmpty();
            assertThat(context.getBeansOfType(InMemoryHanokListStore.class)).isEmpty();
            assertThat(context.getBean(MonthlyHanokEditionService.class)).isNotNull();
            assertThat(context.getBean(ScreenHanokQueryService.class)).isNotNull();
            assertThat(context.getBean(ScreenHanokIngestionService.class)).isNotNull();
        }
    }

    private SourceRecord row(String id, String title, String canonicalCategory) {
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("contentid", id);
        fields.put("contenttypeid", "12");
        fields.put("title", title);
        fields.put("lDongRegnCd", "11");
        fields.put("lDongSignguCd", "110");
        fields.put("lclsSystm1", "VE");
        fields.put("lclsSystm2", "VE01");
        fields.put("lclsSystm3", "VE010100");
        fields.put("mapx", "126.98");
        fields.put("mapy", "37.58");
        fields.put("addr1", "서울 종로구");
        fields.put("firstimage", "https://images.example/" + id + ".jpg");
        if (canonicalCategory != null) fields.put("canonicalcategory", canonicalCategory);
        return new SourceRecord("kto-tourapi-korean", "areaBasedList2", Map.copyOf(fields));
    }

    private SourceRecord invalidCoordinateRow(String id, String title) {
        var fields = new java.util.LinkedHashMap<>(row(id, title, "HISTORIC_SITE").fields());
        fields.put("mapx", "not-a-coordinate");
        return new SourceRecord("kto-tourapi-korean", "areaBasedList2", Map.copyOf(fields));
    }

    private int queryCount(String table) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static String url() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
    }
}

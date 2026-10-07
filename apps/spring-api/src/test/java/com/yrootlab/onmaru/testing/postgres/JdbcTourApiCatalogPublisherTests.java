package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailQueryService;
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
import com.yrootlab.onmaru.persistence.catalog.JdbcMapInfoQueryRepository;
import com.yrootlab.onmaru.persistence.catalog.JdbcMapViewportQueryRepository;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoBounds;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoCategory;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoSqlQuery;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportQuery;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoPlaceItem;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Optional;

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
    void publishesLocatedPlaceWhenRegionIsUnknown() throws Exception {
        var publisher = new JdbcTourApiCatalogPublisher(dataSource);
        var fetchedAt = Instant.parse("2026-09-27T03:00:00Z");
        var session = publisher.start(fetchedAt);
        var page = publisher.stagePage(session, List.of(row("unknown-region", "지역 미상 장소", "HANOK")));
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE onmaru.catalog_place_versions SET region_id = NULL");
            statement.executeUpdate("UPDATE onmaru.catalog_kto_korean_content_versions "
                    + "SET ldong_regn_cd = NULL, ldong_signgu_cd = NULL");
        }

        publisher.complete(session, page.rawCount(), page.rawCount(), page.publishedCount(),
                page.quarantinedCount(), page.skippedCount(), fetchedAt);

        assertThat(queryCount("onmaru.map_place_read_projection WHERE sido_code IS NULL AND sigungu_code IS NULL"))
                .isEqualTo(1);
        assertThat(queryCount("onmaru.map_projection_publications")).isEqualTo(1);
    }

    @Test
    void backfillsAnOlderActiveRevisionWithoutRegionCodes() throws Exception {
        var publisher = new JdbcTourApiCatalogPublisher(dataSource);
        var fetchedAt = Instant.parse("2026-09-27T03:00:00Z");
        var session = publisher.start(fetchedAt);
        var page = publisher.stagePage(session, List.of(row("legacy-1", "지역 미상 장소", "HANOK")));
        publisher.complete(session, page.rawCount(), page.rawCount(), page.publishedCount(),
                page.quarantinedCount(), page.skippedCount(), fetchedAt);

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE onmaru.catalog_place_versions SET region_id = NULL");
            statement.executeUpdate("UPDATE onmaru.catalog_kto_korean_content_versions "
                    + "SET ldong_regn_cd = NULL, ldong_signgu_cd = NULL");
            statement.executeUpdate("DELETE FROM onmaru.map_projection_publications");
            statement.executeUpdate("DELETE FROM onmaru.map_scope_count_projection");
            statement.executeUpdate("DELETE FROM onmaru.map_place_category_projection");
            statement.executeUpdate("DELETE FROM onmaru.map_place_read_projection");

            var resource = JdbcTourApiCatalogPublisherTests.class.getResourceAsStream(
                    "/db/migration/baseline/V037__553_backfill_active_map_projection.sql");
            assertThat(resource).isNotNull();
            var migration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            statement.execute(migration);
            statement.execute(migration);
        }

        assertThat(queryCount("onmaru.map_place_read_projection")).isEqualTo(1);
        assertThat(queryCount("onmaru.map_place_category_projection")).isEqualTo(1);
        assertThat(queryCount("onmaru.map_projection_publications")).isEqualTo(1);
        assertThat(queryCount("onmaru.map_place_read_projection WHERE sido_code IS NULL AND sigungu_code IS NULL"))
                .isEqualTo(1);
        var result = new JdbcMapInfoQueryRepository(dataSource).find(new MapInfoSqlQuery(
                null, List.of(), null, null, "NAME", null, null, 10, null, null));
        assertThat(result.items()).extracting(MapInfoPlaceItem::name).containsExactly("지역 미상 장소");
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
        var mapList = new JdbcMapInfoQueryRepository(dataSource).find(new MapInfoSqlQuery(
                null, List.of(), null, new MapInfoBounds(126.9, 37.5, 127.1, 37.7),
                "NAME", null, null, 30, null, null));
        assertThat(mapList.totalCount()).isEqualTo(2);
        assertThat(mapList.items()).extracting(item -> item.name())
                .containsExactlyInAnyOrder("북촌 한옥", "전주 남부시장");
        var viewport = new JdbcMapViewportQueryRepository(dataSource).find(new MapInfoViewportQuery(
                new MapInfoBounds(126.9, 37.5, 127.1, 37.7), 5, MapInfoCategory.ALL,
                null, null, "ko-KR", 500));
        assertThat(viewport.totalCountInViewport()).isEqualTo(2);
        assertThat(viewport.renderMode().name()).isEqualTo("PLACE");
        assertThat(queryCount("onmaru.map_projection_publications")).isEqualTo(1);
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
    void hanokSnapshotAndDetailShareEligibilityForHistoricPlaces() throws Exception {
        var publisher = new JdbcTourApiCatalogPublisher(dataSource);
        var fetchedAt = Instant.parse("2026-09-27T03:00:00Z");
        var session = publisher.start(fetchedAt);
        var overviewFields = new java.util.LinkedHashMap<>(row("4862", "전통 문화 마을", "HISTORIC_SITE").fields());
        overviewFields.put("overview", "전통 한옥을 둘러보는 마을");
        var page = publisher.stagePage(session, List.of(
                row("4861", "북촌한옥마을", "HISTORIC_SITE"),
                new SourceRecord("kto-tourapi-korean", "areaBasedList2", Map.copyOf(overviewFields)),
                row("4863", "문화 궁전", "HISTORIC_SITE"),
                row("4864", "전통 숙소", "HANOK_STAY")));
        publisher.complete(session, page.rawCount(), page.rawCount(),
                page.publishedCount(), page.quarantinedCount(), page.skippedCount(), fetchedAt);

        var snapshot = new JdbcCatalogPlaceSnapshotStore(dataSource).findPublishedHanokSnapshot();
        assertThat(snapshot).extracting(place -> place.placeId())
                .containsExactly("p-tourapi-4861", "p-tourapi-4862", "p-tourapi-4864");
        var details = new PlaceDetailQueryService(new JdbcPlaceDetailStore(dataSource),
                (memberId, placeId) -> false);
        for (var place : snapshot) {
            assertThat(details.findHanok(place.placeId(), Optional.empty()))
                    .hasValueSatisfying(detail -> {
                        assertThat(detail.placeId()).isEqualTo(place.placeId());
                        assertThat(detail.category().name()).isEqualTo(place.category().name());
                    });
        }
        assertThat(details.findHanok("p-tourapi-4863", Optional.empty())).isEmpty();
    }

    @Test
    void hanokMapFilterReturnsOnlyTheFourHanokCatalogCategories() throws Exception {
        var publisher = new JdbcTourApiCatalogPublisher(dataSource);
        var fetchedAt = Instant.parse("2026-09-27T03:00:00Z");
        var session = publisher.start(fetchedAt);
        var page = publisher.stagePage(session, List.of(
                row("2101", "한옥 명소", "HANOK"),
                row("2102", "한옥 숙소", "HANOK_STAY"),
                row("2103", "한옥 카페", "HANOK_CAFE"),
                row("2104", "한옥 체험", "HANOK_EXPERIENCE"),
                row("2105", "일반 문화재", "HISTORIC_SITE"),
                row("2106", "일반 카페", "CAFE")));
        publisher.complete(session, page.rawCount(), page.rawCount(), page.publishedCount(),
                page.quarantinedCount(), page.skippedCount(), fetchedAt);
        seedMapRegions();
        seedMapBoundary("11");
        seedMapBoundary("11:110");
        var hanokCategories = List.of("HANOK", "HANOK_STAY", "HANOK_CAFE", "HANOK_EXPERIENCE");

        var list = new JdbcMapInfoQueryRepository(dataSource).find(new MapInfoSqlQuery(
                null, hanokCategories, null, null, "NAME", null, null, 30, null, null));
        var viewport = new JdbcMapViewportQueryRepository(dataSource).find(new MapInfoViewportQuery(
                new MapInfoBounds(126.9, 37.5, 127.1, 37.7), 5,
                MapInfoCategory.valueOf("HANOK"), null, null, "ko-KR", 500));
        var clusterViewport = new JdbcMapViewportQueryRepository(dataSource).find(new MapInfoViewportQuery(
                new MapInfoBounds(126.9, 37.5, 127.1, 37.7), 7,
                MapInfoCategory.valueOf("HANOK"), null, null, "ko-KR", 500));
        var districtViewport = new JdbcMapViewportQueryRepository(dataSource).find(new MapInfoViewportQuery(
                new MapInfoBounds(126.9, 37.5, 127.1, 37.7), 9,
                MapInfoCategory.valueOf("HANOK"), null, null, "ko-KR", 500));
        var regionViewport = new JdbcMapViewportQueryRepository(dataSource).find(new MapInfoViewportQuery(
                new MapInfoBounds(126.9, 37.5, 127.1, 37.7), 12,
                MapInfoCategory.valueOf("HANOK"), null, null, "ko-KR", 500));

        assertThat(list.totalCount()).isEqualTo(4);
        assertThat(list.items()).extracting(MapInfoPlaceItem::name)
                .containsExactlyInAnyOrder("한옥 명소", "한옥 숙소", "한옥 카페", "한옥 체험");
        assertThat(viewport.totalCountInViewport()).isEqualTo(4);
        assertThat(viewport.items()).extracting(item -> item.name())
                .containsExactlyInAnyOrder("한옥 명소", "한옥 숙소", "한옥 카페", "한옥 체험");
        assertThat(clusterViewport.totalCountInViewport()).isEqualTo(4);
        assertThat(clusterViewport.items()).hasSize(1);
        assertThat(clusterViewport.items().getFirst().count()).isEqualTo(4);
        assertThat(clusterViewport.items().getFirst().categoryCounts().keySet())
                .containsExactlyInAnyOrder("HANOK", "HANOK_STAY", "HANOK_CAFE", "HANOK_EXPERIENCE");
        assertThat(clusterViewport.items().getFirst().categoryCounts().values()).containsOnly(1L);
        assertThat(districtViewport.items()).extracting(item -> item.count()).containsExactly(4L);
        assertThat(regionViewport.items()).extracting(item -> item.count()).containsExactly(4L);
    }

    @Test
    void nameKeysetCursorFollowsNameOrderAcrossRegionsWithoutOmission() throws Exception {
        var publisher = new JdbcTourApiCatalogPublisher(dataSource);
        var session = publisher.start(Instant.parse("2026-09-27T03:00:00Z"));
        var page = publisher.stagePage(session, List.of(
                rowWithRegion("3001", "가 이름", "HANOK", "11", "110", "126.98", "37.58"),
                rowWithRegion("3002", "나 이름", "HANOK", "26", "260", "129.07", "35.18"),
                rowWithRegion("3003", "다 이름", "HANOK", "11", "110", "126.99", "37.59")));
        publisher.complete(session, page.rawCount(), page.rawCount(), page.publishedCount(),
                page.quarantinedCount(), page.skippedCount(), Instant.parse("2026-09-27T03:01:00Z"));

        var repository = new JdbcMapInfoQueryRepository(dataSource);
        var first = repository.find(new MapInfoSqlQuery(
                null, List.of(), null, null, "NAME", null, null, 2, null, null));
        var second = repository.find(new MapInfoSqlQuery(
                first.snapshot().id(), List.of(), null, null, "NAME", null, null, 2,
                first.lastCursor(), null));

        assertThat(first.items()).extracting(MapInfoPlaceItem::name)
                .containsExactly("가 이름", "나 이름");
        assertThat(second.items()).extracting(MapInfoPlaceItem::name)
                .containsExactly("다 이름");
    }

    @Test
    @Tag("performance")
    void thirtyThousandPublishedPlacesStayWithinMapInfoLatencyBudgets() throws Exception {
        var publisher = new JdbcTourApiCatalogPublisher(dataSource);
        var session = publisher.start(Instant.parse("2026-09-27T03:00:00Z"));
        var records = new ArrayList<SourceRecord>(30_000);
        for (int i = 0; i < 30_000; i++) {
            records.add(rowWithRegion("400000" + i, "성능 장소 " + String.format("%05d", i), "HANOK",
                    "11", "110", "126.90" + (i % 10), "37.50" + (i % 10)));
        }
        var page = publisher.stagePage(session, records);
        publisher.complete(session, page.rawCount(), page.rawCount(), page.publishedCount(),
                page.quarantinedCount(), page.skippedCount(), Instant.parse("2026-09-27T03:01:00Z"));

        var listRepository = new JdbcMapInfoQueryRepository(dataSource);
        var viewportRepository = new JdbcMapViewportQueryRepository(dataSource);
        int[] zoomProfiles = {4, 7, 9, 12};
        for (int i = 0; i < 3; i++) {
            listRepository.find(new MapInfoSqlQuery(null, List.of(), null, null, "NAME", null, null, 30, null, null));
            for (int zoom : zoomProfiles) {
                viewportRepository.find(new MapInfoViewportQuery(
                        new MapInfoBounds(126.8, 37.4, 127.1, 37.8), zoom, MapInfoCategory.ALL,
                        null, null, "ko-KR", 500));
            }
        }

        var listSamples = new long[20];
        var viewportSamples = new long[zoomProfiles.length][20];
        for (int i = 0; i < 20; i++) {
            listSamples[i] = elapsedNanos(() -> listRepository.find(
                    new MapInfoSqlQuery(null, List.of(), null, null, "NAME", null, null, 30, null, null)));
            for (int profile = 0; profile < zoomProfiles.length; profile++) {
                int zoom = zoomProfiles[profile];
                viewportSamples[profile][i] = elapsedNanos(() -> viewportRepository.find(new MapInfoViewportQuery(
                        new MapInfoBounds(126.8, 37.4, 127.1, 37.8), zoom, MapInfoCategory.ALL,
                        null, null, "ko-KR", 500)));
            }
        }

        assertThat(percentile95Millis(listSamples)).isLessThanOrEqualTo(200.0);
        for (long[] samples : viewportSamples) {
            assertThat(percentile95Millis(samples)).isLessThanOrEqualTo(500.0);
        }
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
    void productionUsesJdbcSnapshotsInsteadOfHardcodedStores() throws Exception {
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
        return rowWithRegion(id, title, canonicalCategory, "11", "110", "126.98", "37.58");
    }

    private SourceRecord rowWithRegion(String id, String title, String canonicalCategory,
                                       String region, String district, String longitude, String latitude) {
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("contentid", id);
        fields.put("contenttypeid", "12");
        fields.put("title", title);
        fields.put("lDongRegnCd", region);
        fields.put("lDongSignguCd", district);
        fields.put("lclsSystm1", "VE");
        fields.put("lclsSystm2", "VE01");
        fields.put("lclsSystm3", "VE010100");
        fields.put("mapx", longitude);
        fields.put("mapy", latitude);
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

    private void seedMapBoundary(String regionCode) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.catalog_region_boundaries
                         (boundary_revision, region_id, geometry, source_name, rights_note, observed_at)
                     SELECT 'map-info-hanok-test', id,
                            ST_Multi(ST_GeomFromText(
                                'POLYGON((126.8 37.4,127.2 37.4,127.2 37.8,126.8 37.8,126.8 37.4))', 4326)),
                            'test', 'test', now()
                     FROM onmaru.catalog_regions
                     WHERE code = ?
                     """)) {
            statement.setString(1, regionCode);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private void seedMapRegions() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO onmaru.catalog_regions (id, parent_id, code, name, level, active)
                    VALUES (md5('map-info-hanok-test|11')::uuid, NULL, '11', '서울', 'SIDO', true)
                    ON CONFLICT (code) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO onmaru.catalog_regions (id, parent_id, code, name, level, active)
                    VALUES (
                        md5('map-info-hanok-test|11:110')::uuid,
                        md5('map-info-hanok-test|11')::uuid,
                        '11:110', '종로구', 'SIGUNGU', true)
                    ON CONFLICT (code) DO NOTHING
                    """);
        }
    }

    private long elapsedNanos(Runnable action) {
        long started = System.nanoTime();
        action.run();
        return System.nanoTime() - started;
    }

    private double percentile95Millis(long[] samples) {
        var sorted = java.util.Arrays.stream(samples).sorted().toArray();
        int index = Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95) - 1);
        return sorted[index] / 1_000_000.0;
    }

    private static String url() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
    }
}

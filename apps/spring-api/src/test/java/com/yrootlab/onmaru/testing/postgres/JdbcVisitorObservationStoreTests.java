package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.insights.observation.ObservationCoverageStatus;
import com.yrootlab.onmaru.insights.observation.ObservationMetric;
import com.yrootlab.onmaru.insights.observation.SpatialLevel;
import com.yrootlab.onmaru.insights.observation.VisitorObservation;
import com.yrootlab.onmaru.persistence.insights.JdbcVisitorObservationStore;
import com.yrootlab.onmaru.persistence.insights.JdbcInsightsQueryStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcVisitorObservationStoreTests {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private DataSource dataSource;
    private UUID activeRevisionId;

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            PostgresTestDatabase.reset(connection);
        }
        Flyway.configure()
                .dataSource(jdbcUrl(), "onmaru_test", "onmaru_test")
                .locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(jdbcUrl(), "onmaru_test", "onmaru_test");
        activeRevisionId = UUID.randomUUID();
        seedRegion("kr-45-jeonju");
        seedActiveDataLabRevision(activeRevisionId);
    }

    @Test
    void returnsOnlyTheLatestCompleteCountFromTheActiveDataLabRevisionAcrossStoreInstances() {
        var store = new JdbcVisitorObservationStore(dataSource);
        store.save(activeRevisionId, complete("2026-09-14", 18240L));
        store.save(activeRevisionId, complete("2026-09-15", 19420L));

        assertThat(new JdbcVisitorObservationStore(dataSource)
                .findLatestCompleteByRegionCodes(Set.of("kr-45-jeonju", "kr-11-seoul")))
                .isEqualTo(Map.of("kr-45-jeonju", 19420L));
    }

    @Test
    void omitsNotAvailableCountsInsteadOfSynthesizingZero() {
        var store = new JdbcVisitorObservationStore(dataSource);
        store.save(activeRevisionId, new VisitorObservation(
                "KTO_DATALAB", "kr-45-jeonju", LocalDate.parse("2026-09-15"),
                ObservationMetric.VISITOR_COUNT, null, "persons", SpatialLevel.SIGUNGU,
                ObservationCoverageStatus.NOT_AVAILABLE, Instant.parse("2026-09-16T00:00:00Z")));

        assertThat(store.findLatestCompleteByRegionCodes(Set.of("kr-45-jeonju"))).isEmpty();
    }

    @Test
    void buildsAdministrativeHeatSpotsFromVisitorObservationsAndCatalogCoordinates() throws Exception {
        var store = new JdbcVisitorObservationStore(dataSource);
        store.save(activeRevisionId, complete("2026-09-15", 19_420L));
        seedActiveCatalogPlace();

        assertThat(new JdbcInsightsQueryStore(dataSource).heatSpots())
                .singleElement()
                .satisfies(spot -> {
                    assertThat(spot.region().regionCode()).isEqualTo("kr-45-jeonju");
                    assertThat(spot.coordinates().lat()).isEqualTo(35.815);
                    assertThat(spot.coordinates().lng()).isEqualTo(127.153);
                    assertThat(spot.visitorCount()).isEqualTo(19_420L);
                    assertThat(spot.congestionScore()).isEqualTo(61.0);
                    assertThat(spot.congestionLevel()).isEqualTo("BUSY");
                });
    }

    private VisitorObservation complete(String basisDate, long value) {
        return new VisitorObservation(
                "KTO_DATALAB", "kr-45-jeonju", LocalDate.parse(basisDate),
                ObservationMetric.VISITOR_COUNT, value, "persons", SpatialLevel.SIGUNGU,
                ObservationCoverageStatus.COMPLETE, Instant.parse("2026-09-16T00:00:00Z"));
    }

    private void seedRegion(String code) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.catalog_regions (id, parent_id, code, name, level, active)
                     VALUES (?, NULL, 'kr-test-parent', '테스트 광역', 'SIDO', true),
                            (?, ?, ?, '전북 전주시', 'SIGUNGU', true)
                     """)) {
            UUID parentId = UUID.randomUUID();
            statement.setObject(1, parentId);
            statement.setObject(2, UUID.randomUUID());
            statement.setObject(3, parentId);
            statement.setString(4, code);
            statement.executeUpdate();
        }
    }

    private void seedActiveDataLabRevision(UUID revisionId) throws Exception {
        try (var connection = dataSource.getConnection();
             var revision = connection.prepareStatement("""
                     INSERT INTO onmaru.catalog_dataset_revisions
                         (id, dataset, status, fetched_at, published_at)
                     VALUES (?, 'kto-datalab-visitor', 'PUBLISHED', now(), now())
                     """ );
             var active = connection.prepareStatement("""
                     INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                     VALUES ('kto-datalab-visitor', ?, now())
                     """)) {
            revision.setObject(1, revisionId);
            revision.executeUpdate();
            active.setObject(1, revisionId);
            active.executeUpdate();
        }
    }

    private void seedActiveCatalogPlace() throws Exception {
        UUID revisionId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.catalog_dataset_revisions
                        (id, dataset, status, fetched_at, published_at)
                    VALUES ('%s', 'kto-korean-tour', 'PUBLISHED', now(), now())
                    """.formatted(revisionId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                    VALUES ('kto-korean-tour', '%s', now())
                    """.formatted(revisionId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_place_identity (id, created_at)
                    VALUES ('%s', now())
                    """.formatted(placeId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_place_sources
                        (id, place_id, provider, dataset, external_id, language, fetched_at)
                    VALUES ('%s', '%s', 'KTO', 'kto-korean-tour', 'heat-test', 'ko-KR', now())
                    """.formatted(sourceId, placeId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_place_versions (
                        revision_id, place_id, source_ref_id, region_id, name, category,
                        address, location, visit_review_eligible, status, normalized_hash
                    ) SELECT
                        '%s', '%s', '%s', region.id, '전주 한옥마을', 'HANOK',
                        '전북 전주시 완산구',
                        ST_SetSRID(ST_MakePoint(127.153, 35.815), 4326)::geography,
                        true, 'ACTIVE', 'heat-test-hash'
                    FROM onmaru.catalog_regions region
                    WHERE region.code = 'kr-45-jeonju'
                    """.formatted(revisionId, placeId, sourceId));
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
    }
}

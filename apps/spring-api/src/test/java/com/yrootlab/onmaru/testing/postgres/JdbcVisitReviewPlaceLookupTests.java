package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.persistence.catalog.JdbcCatalogPublicPlaceIdStore;
import com.yrootlab.onmaru.persistence.community.JdbcVisitReviewPlaceLookup;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcVisitReviewPlaceLookupTests {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private DataSource dataSource;
    private UUID placeId;

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
        placeId = seedEligibleActivePlace();
    }

    @Test
    void resolvesOnlyAnEligiblePlaceInTheActiveCatalogRevision() throws Exception {
        new JdbcCatalogPublicPlaceIdStore(dataSource).register("p-jeonju-hanok-village", placeId);
        var lookup = new JdbcVisitReviewPlaceLookup(dataSource);

        assertThat(lookup.findEligiblePlace("p-jeonju-hanok-village"))
                .hasValueSatisfying(place -> {
                    assertThat(place.placeName()).isEqualTo("전주 한옥마을");
                    assertThat(place.regionCode()).isEqualTo("kr-45-jeonju");
                    assertThat(place.lat()).isEqualTo(35.8151);
                    assertThat(place.lng()).isEqualTo(127.1530);
                });

        try (var connection = dataSource.getConnection();
             var update = connection.prepareStatement("""
                     UPDATE onmaru.catalog_place_versions
                     SET visit_review_eligible = false
                     WHERE place_id = ?
                     """)) {
            update.setObject(1, placeId);
            update.executeUpdate();
        }
        assertThat(lookup.findEligiblePlace("p-jeonju-hanok-village")).isEmpty();
    }

    private UUID seedEligibleActivePlace() throws Exception {
        var now = OffsetDateTime.of(2026, 9, 24, 0, 0, 0, 0, ZoneOffset.UTC);
        var revisionId = UUID.randomUUID();
        var sourceId = UUID.randomUUID();
        var regionId = UUID.randomUUID();
        var identityId = UUID.randomUUID();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var identity = connection.prepareStatement("INSERT INTO onmaru.catalog_place_identity (id, created_at) VALUES (?, ?)");
                 var region = connection.prepareStatement("INSERT INTO onmaru.catalog_regions (id, code, name, level, active) VALUES (?, ?, ?, 'SIGUNGU', true)");
                 var revision = connection.prepareStatement("INSERT INTO onmaru.catalog_dataset_revisions (id, dataset, status, fetched_at, published_at) VALUES (?, 'KTO', 'PUBLISHED', ?, ?)");
                 var active = connection.prepareStatement("INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at) VALUES ('KTO', ?, ?)");
                 var source = connection.prepareStatement("INSERT INTO onmaru.catalog_place_sources (id, place_id, provider, dataset, external_id, language, fetched_at) VALUES (?, ?, 'KTO', 'KTO', '123', 'ko', ?)");
                 var version = connection.prepareStatement("""
                         INSERT INTO onmaru.catalog_place_versions (
                             revision_id, place_id, source_ref_id, region_id, name, category, address, location,
                             overview, visit_review_eligible, status, normalized_hash
                         ) VALUES (?, ?, ?, ?, '전주 한옥마을', 'HANOK_VILLAGE', '전주시',
                                   ST_SetSRID(ST_MakePoint(127.1530, 35.8151), 4326)::geography,
                                   '한옥마을', true, 'ACTIVE', 'hash')
                         """)) {
                identity.setObject(1, identityId);
                identity.setObject(2, now);
                identity.executeUpdate();
                region.setObject(1, regionId);
                region.setString(2, "kr-45-jeonju");
                region.setString(3, "전주시");
                region.executeUpdate();
                revision.setObject(1, revisionId);
                revision.setObject(2, now);
                revision.setObject(3, now);
                revision.executeUpdate();
                active.setObject(1, revisionId);
                active.setObject(2, now);
                active.executeUpdate();
                source.setObject(1, sourceId);
                source.setObject(2, identityId);
                source.setObject(3, now);
                source.executeUpdate();
                version.setObject(1, revisionId);
                version.setObject(2, identityId);
                version.setObject(3, sourceId);
                version.setObject(4, regionId);
                version.executeUpdate();
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
        return identityId;
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
    }
}

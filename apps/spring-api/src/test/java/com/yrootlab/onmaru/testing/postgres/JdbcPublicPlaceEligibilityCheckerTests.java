package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Filters;
import com.yrootlab.onmaru.persistence.catalog.journey.JdbcPublicPlaceEligibilityChecker;
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
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcPublicPlaceEligibilityCheckerTests {

    private static final String DATASET = "kto-korean-tour";
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private DataSource dataSource;
    private JdbcPublicPlaceEligibilityChecker checker;

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
        checker = new JdbcPublicPlaceEligibilityChecker(dataSource);
        seedCatalog();
    }

    @Test
    void approvesActivePublishedPlacesMatchingFiltersAndRejectsHiddenStagingOrExcluded() {
        var allCandidates = List.of(
                "p-jeonju-active",
                "p-jeonju-hidden",
                "p-seoul-active",
                "p-staging-only",
                "p-excluded",
                "p-unknown");

        Set<String> jeonjuEligible = checker.eligiblePlaceIds(
                allCandidates,
                new Filters(
                        "sha256:" + "a".repeat(64),
                        true,
                        null,
                        List.of("kr-45-jeonju"),
                        null,
                        List.of(),
                        List.of("p-excluded")));

        assertThat(jeonjuEligible).containsExactly("p-jeonju-active");

        Set<String> allRegionsEligible = checker.eligiblePlaceIds(
                allCandidates,
                new Filters(
                        "sha256:" + "a".repeat(64),
                        true,
                        null,
                        null,
                        List.of("HANOK_VILLAGE"),
                        List.of(),
                        List.of()));

        assertThat(allRegionsEligible).containsExactlyInAnyOrder("p-jeonju-active", "p-excluded");
        assertThat(checker.eligiblePlaceIds(List.of(), null)).isEmpty();
    }

    private void seedCatalog() throws SQLException {
        UUID jeonjuId = UUID.randomUUID();
        UUID seoulId = UUID.randomUUID();
        UUID publishedRevId = UUID.randomUUID();
        UUID stagingRevId = UUID.randomUUID();

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.catalog_regions (id, parent_id, code, name, level, active) VALUES
                    ('%s', NULL, 'kr-45-jeonju', '전북 전주', 'SIGUNGU', true),
                    ('%s', NULL, 'kr-11-seoul', '서울', 'SIDO', true)
                    """.formatted(jeonjuId, seoulId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_dataset_revisions (id, dataset, status, fetched_at, published_at) VALUES
                    ('%s', '%s', 'PUBLISHED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    ('%s', '%s', 'STAGING', CURRENT_TIMESTAMP, NULL)
                    """.formatted(publishedRevId, DATASET, stagingRevId, DATASET));
            statement.execute("""
                    INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                    VALUES ('%s', '%s', CURRENT_TIMESTAMP)
                    """.formatted(DATASET, publishedRevId));
        }

        insertPlace(publishedRevId, jeonjuId, "p-jeonju-active", "전주 한옥마을", "HANOK_VILLAGE", "ACTIVE");
        insertPlace(publishedRevId, jeonjuId, "p-jeonju-hidden", "비공개 장소", "HANOK_VILLAGE", "HIDDEN");
        insertPlace(publishedRevId, seoulId, "p-seoul-active", "북촌 한옥마을", "CULTURAL_HERITAGE", "ACTIVE");
        insertPlace(publishedRevId, jeonjuId, "p-excluded", "제외된 한옥", "HANOK_VILLAGE", "ACTIVE");
        insertPlace(stagingRevId, jeonjuId, "p-staging-only", "스테이징 전용", "HANOK_VILLAGE", "ACTIVE");
    }

    private void insertPlace(
            UUID revisionId,
            UUID regionId,
            String publicId,
            String name,
            String category,
            String status) throws SQLException {
        UUID placeId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.catalog_place_identity (id, created_at)
                    VALUES ('%s', CURRENT_TIMESTAMP)
                    """.formatted(placeId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_place_public_ids (public_id, place_id)
                    VALUES ('%s', '%s')
                    """.formatted(publicId, placeId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_place_sources (id, place_id, provider, dataset, external_id, language, fetched_at)
                    VALUES ('%s', '%s', 'KTO', '%s', '%s', 'ko', CURRENT_TIMESTAMP)
                    """.formatted(sourceId, placeId, DATASET, "ext-" + publicId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_place_versions (
                        revision_id, place_id, source_ref_id, region_id, name, category,
                        visit_review_eligible, status, normalized_hash
                    ) VALUES (
                        '%s', '%s', '%s', '%s', '%s', '%s', true, '%s'::onmaru.catalog_place_status, 'hash-%s'
                    )
                    """.formatted(revisionId, placeId, sourceId, regionId, name, category, status, publicId));
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
    }
}

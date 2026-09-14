package com.yrootlab.onmaru.testing.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsightsMigrationTests {

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

    @BeforeAll
    static void startPostgres() {
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @Test
    void migratesInsightsObservationTargetAndLinkSchema() throws Exception {
        resetAndMigrate();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'onmaru'
                      AND table_name IN (
                        'insights_visitor_observations',
                        'insights_tourism_targets',
                        'insights_target_place_links',
                        'insights_concentration_observations'
                      )
                    """)).isEqualTo(4);
        }
    }

    @Test
    void storesValidVisitorAndConcentrationObservationsWithProvenance() throws Exception {
        resetAndMigrate();
        var regionId = UUID.randomUUID();
        var revisionId = UUID.randomUUID();
        var placeId = UUID.randomUUID();
        var targetId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertCatalogFixtures(statement, regionId, revisionId, placeId, "insights-datalab");
            insertTarget(statement, targetId, regionId, "kto-datalab", "jeonju-hanok");
            statement.execute("""
                    INSERT INTO onmaru.insights_target_place_links (
                        target_id, place_id, match_method, verified_at
                    ) VALUES (
                        '%s', '%s', 'MANUAL_VERIFIED', '2026-09-15T00:00:00Z'
                    )
                    """.formatted(targetId, placeId));
            statement.execute("""
                    INSERT INTO onmaru.insights_visitor_observations (
                        revision_id, region_id, basis_date, visitor_type, provider,
                        spatial_level, visitor_count, source_observed_at, fetched_at
                    ) VALUES (
                        '%s', '%s', '2026-09-14', 'DOMESTIC', 'KTO_DATALAB',
                        'CITY', 18240, '2026-09-15T00:00:00Z', '2026-09-15T00:05:00Z'
                    )
                    """.formatted(revisionId, regionId));
            statement.execute("""
                    INSERT INTO onmaru.insights_concentration_observations (
                        revision_id, target_id, basis_date, metric_type, value, unit,
                        source_observed_at, fetched_at
                    ) VALUES (
                        '%s', '%s', '2026-09-14', 'CONGESTION_SCORE', 72.4, 'score',
                        '2026-09-15T00:00:00Z', '2026-09-15T00:05:00Z'
                    )
                    """.formatted(revisionId, targetId));

            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.insights_visitor_observations"))
                    .isEqualTo(1);
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.insights_concentration_observations"))
                    .isEqualTo(1);
        }
    }

    @Test
    void rejectsDuplicateTargetsInvalidUnitsNegativeValuesAndOrphanRevisions() throws Exception {
        resetAndMigrate();
        var regionId = UUID.randomUUID();
        var revisionId = UUID.randomUUID();
        var placeId = UUID.randomUUID();
        var targetId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertCatalogFixtures(statement, regionId, revisionId, placeId, "insights-datalab");
            insertTarget(statement, targetId, regionId, "kto-datalab", "jeonju-hanok");

            assertThatThrownBy(() -> insertTarget(statement, UUID.randomUUID(), regionId, "kto-datalab", "jeonju-hanok"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("insights_tourism_targets_provider_source_target_key_uq");

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO onmaru.insights_visitor_observations (
                        revision_id, region_id, basis_date, visitor_type, provider,
                        spatial_level, visitor_count, fetched_at
                    ) VALUES (
                        '%s', '%s', '2026-09-14', 'DOMESTIC', 'KTO_DATALAB',
                        'CITY', -1, '2026-09-15T00:05:00Z'
                    )
                    """.formatted(revisionId, regionId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("insights_visitor_observations_count_ck");

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO onmaru.insights_visitor_observations (
                        revision_id, region_id, basis_date, visitor_type, provider,
                        spatial_level, visitor_count, fetched_at
                    ) VALUES (
                        gen_random_uuid(), '%s', '2026-09-14', 'DOMESTIC', 'KTO_DATALAB',
                        'CITY', 1, '2026-09-15T00:05:00Z'
                    )
                    """.formatted(regionId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("insights_visitor_observations_revision_id_fkey");

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO onmaru.insights_concentration_observations (
                        revision_id, target_id, basis_date, metric_type, value, unit, fetched_at
                    ) VALUES (
                        '%s', '%s', '2026-09-14', 'CONGESTION_SCORE', 72.4,
                        'people', '2026-09-15T00:05:00Z'
                    )
                    """.formatted(revisionId, targetId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("insights_concentration_observations_unit_ck");

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO onmaru.insights_concentration_observations (
                        revision_id, target_id, basis_date, metric_type, value, unit, fetched_at
                    ) VALUES (
                        '%s', '%s', '2026-09-14', 'CONGESTION_SCORE', 120,
                        'score', '2026-09-15T00:05:00Z'
                    )
                    """.formatted(revisionId, targetId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("insights_concentration_observations_value_range_ck");
        }
    }

    private static void insertCatalogFixtures(
            Statement statement,
            UUID regionId,
            UUID revisionId,
            UUID placeId,
            String dataset
    ) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.catalog_regions (id, code, name, level, active)
                VALUES ('%s', 'kr-45-jeonju', '전북 전주시', 'SIGUNGU', true)
                """.formatted(regionId));
        statement.execute("""
                INSERT INTO onmaru.catalog_dataset_revisions (
                    id, dataset, status, source_observed_at, fetched_at
                ) VALUES (
                    '%s', '%s', 'STAGING', '2026-09-15T00:00:00Z',
                    '2026-09-15T00:05:00Z'
                )
                """.formatted(revisionId, dataset));
        statement.execute("""
                INSERT INTO onmaru.catalog_place_identity (id, created_at)
                VALUES ('%s', '2026-09-15T00:00:00Z')
                """.formatted(placeId));
    }

    private static void insertTarget(
            Statement statement,
            UUID targetId,
            UUID regionId,
            String provider,
            String sourceTargetKey
    ) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.insights_tourism_targets (
                    id, provider, source_target_key, region_id, source_name
                ) VALUES (
                    '%s', '%s', '%s', '%s', '전주 한옥마을'
                )
                """.formatted(targetId, provider, sourceTargetKey, regionId));
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

    private static int countRows(Statement statement, String sql) throws Exception {
        try (var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(),
                postgres.getMappedPort(POSTGRES_PORT),
                DATABASE);
    }
}

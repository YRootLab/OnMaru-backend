package com.yrootlab.onmaru.testing.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StagingFixtureTests {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private static final Path SEED = Path.of("../../infra/lightsail/staging/seed.sql");

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void migrateEmptyDatabase() throws Exception {
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
    }

    @Test
    void seedsConnectedPublicApiFixturesIdempotently() throws Exception {
        executeSeedForTestDatabase();
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE onmaru.catalog_place_versions
                    SET overview = 'drifted staging value'
                    WHERE place_id = '54500000-0000-4000-8000-000000000021'
                    """);
            statement.executeUpdate("""
                    UPDATE onmaru.map_place_read_projection
                    SET summary = 'drifted staging value'
                    WHERE place_id = '54500000-0000-4000-8000-000000000022'
                    """);
        }
        executeSeedForTestDatabase();

        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            assertThat(count(connection, "onmaru.catalog_place_versions")).isEqualTo(4);
            assertThat(count(connection, "onmaru.map_place_read_projection")).isEqualTo(4);
            assertThat(count(connection, "onmaru.community_visit_reviews")).isEqualTo(3);
            assertThat(count(connection, "onmaru.audio_story_versions")).isEqualTo(3);
            assertThat(value(connection, """
                    SELECT overview FROM onmaru.catalog_place_versions
                    WHERE place_id = '54500000-0000-4000-8000-000000000021'
                    """)).isEqualTo("실제 관광지가 아닌 합성 테스트 데이터입니다.");
            assertThat(value(connection, """
                    SELECT summary FROM onmaru.map_place_read_projection
                    WHERE place_id = '54500000-0000-4000-8000-000000000022'
                    """)).isEqualTo("선택 필드가 적은 합성 테스트 데이터입니다.");
            assertThat(value(connection, """
                    SELECT (published_at AT TIME ZONE 'UTC')::text FROM onmaru.map_projection_publications
                    WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                    """)).startsWith("2026-10-06 00:00:00");
            assertThat(value(connection, """
                    SELECT string_agg(canonical_category, ',' ORDER BY canonical_category, place_id)
                    FROM onmaru.map_place_category_projection
                    WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                    """)).isEqualTo("CAFE,MARKET,SPOT,SPOT");
            assertThat(value(connection, """
                    SELECT string_agg(scope_type || ':' || region_code || ':' || canonical_category || ':' || place_count,
                                      ',' ORDER BY scope_type, canonical_category)
                    FROM onmaru.map_scope_count_projection
                    WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                    """)).isEqualTo("DISTRICT:STG-01:CAFE:1,DISTRICT:STG-01:MARKET:1,DISTRICT:STG-01:SPOT:2,REGION:STG:CAFE:1,REGION:STG:MARKET:1,REGION:STG:SPOT:2");

            try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                    SELECT count(*)
                    FROM onmaru.catalog_active_datasets active
                    JOIN onmaru.catalog_place_versions place ON place.revision_id = active.revision_id
                    JOIN onmaru.catalog_place_public_ids public_id ON public_id.place_id = place.place_id
                    JOIN onmaru.catalog_place_image_versions image
                      ON image.revision_id = place.revision_id AND image.place_id = place.place_id
                    JOIN onmaru.catalog_place_content_tag_versions tag
                      ON tag.revision_id = place.revision_id AND tag.place_id = place.place_id
                    JOIN onmaru.community_visit_reviews review ON review.place_id = place.place_id
                    JOIN onmaru.audio_place_odii_links audio_link ON audio_link.place_id = place.place_id
                    JOIN onmaru.audio_story_versions story ON story.spot_id = audio_link.spot_id
                    WHERE active.dataset = 'kto-korean-tour'
                      AND public_id.public_id = 'p-staging-hanok-a'
                      AND place.status = 'ACTIVE'
                      AND review.status = 'PUBLISHED'
                      AND story.status = 'ACTIVE'
                    """)) {
                rows.next();
                assertThat(rows.getInt(1)).isGreaterThan(0);
            }
        }
    }

    @Test
    void refusesToSeedAnyDatabaseExceptStaging() throws Exception {
        var sql = seedSql();
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute(sql))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Refusing staging seed");
        }
    }

    private void executeSeedForTestDatabase() throws Exception {
        var sql = seedSql().replace("current_database() <> 'onmaru_staging'", "current_database() <> 'onmaru_test'");
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String seedSql() throws Exception {
        return Files.readString(SEED).replace("\\set ON_ERROR_STOP on", "");
    }

    private long count(java.sql.Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private String value(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
    }
}

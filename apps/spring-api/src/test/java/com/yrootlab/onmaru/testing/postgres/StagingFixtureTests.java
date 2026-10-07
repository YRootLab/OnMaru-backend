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
import java.sql.PreparedStatement;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.map_place_read_projection
                    WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                    """)).isEqualTo(100);
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.community_visit_reviews
                    WHERE status = 'PUBLISHED' AND (
                      id::text LIKE '54500675-%'
                      OR id IN (
                        '54500000-0000-4000-8000-000000000111',
                        '54500000-0000-4000-8000-000000000112'
                      )
                    )
                    """)).isEqualTo(65);
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.audio_story_versions
                    WHERE revision_id = '54500000-0000-4000-8000-000000000011'
                      AND status = 'ACTIVE'
                    """)).isEqualTo(65);
            assertThat(value(connection, """
                    SELECT overview FROM onmaru.catalog_place_versions
                    WHERE place_id = '54500000-0000-4000-8000-000000000021'
                    """)).isEqualTo("실제 관광지가 아닌 합성 테스트 데이터입니다.");
            assertThat(value(connection, """
                    SELECT summary FROM onmaru.map_place_read_projection
                    WHERE place_id = '54500000-0000-4000-8000-000000000022'
                    """)).isEqualTo("선택 필드가 적은 합성 테스트 데이터입니다.");
            assertThat(longValue(connection, """
                    SELECT count(DISTINCT sido_code)
                    FROM onmaru.map_place_read_projection
                    WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                    """)).isGreaterThanOrEqualTo(4);
            assertThat(value(connection, """
                    SELECT (published_at AT TIME ZONE 'UTC')::text FROM onmaru.map_projection_publications
                    WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                    """)).startsWith("2026-10-06 00:00:00");
            assertThat(value(connection, """
                    SELECT string_agg(canonical_category || ':' || count_value, ',' ORDER BY canonical_category)
                    FROM (
                      SELECT canonical_category, count(*)::text AS count_value
                      FROM onmaru.map_place_category_projection
                      WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                      GROUP BY canonical_category
                    ) counts
                    """)).contains("CAFE:", "MARKET:", "SPOT:");
            assertThat(longValue(connection, """
                    SELECT row_count FROM onmaru.map_projection_publications
                    WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                      AND projection_name = 'map_place_read_projection'
                    """)).isEqualTo(100);
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.map_scope_count_projection
                    WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                    """)).isPositive();

            assertThreeKeysetPages(connection,
                    "onmaru.community_visit_reviews",
                    "status = 'PUBLISHED' AND public_place_id IS NOT NULL AND latitude IS NOT NULL " +
                            "AND longitude IS NOT NULL AND (id::text LIKE '54500675-%' OR id IN (" +
                            "'54500000-0000-4000-8000-000000000111', " +
                            "'54500000-0000-4000-8000-000000000112'))",
                    "created_at",
                    "id");
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.community_visit_reviews
                    WHERE status = 'PUBLISHED'
                      AND id = '54500000-0000-4000-8000-000000000113'
                    """)).isZero();
            assertThreeKeysetPages(connection,
                    "onmaru.audio_story_versions",
                    "revision_id = '54500000-0000-4000-8000-000000000011' AND status = 'ACTIVE'",
                    "source_modified_at",
                    "story_id");

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

    private void assertThreeKeysetPages(
            java.sql.Connection connection,
            String table,
            String predicate,
            String timestampColumn,
            String idColumn) throws SQLException {
        var completeOrderedIds = readIds(connection, """
                SELECT %1$s::text FROM %2$s
                WHERE %3$s
                ORDER BY %4$s DESC, %1$s DESC
                """.formatted(idColumn, table, predicate, timestampColumn));
        var pagedIds = new ArrayList<String>();
        var pageSizes = new ArrayList<Integer>();
        var hasMoreByPage = new ArrayList<Boolean>();
        OffsetDateTime cursorTimestamp = null;
        UUID cursorId = null;
        boolean hasMore;

        do {
            var sql = """
                    SELECT %1$s::text AS fixture_id, %2$s, %1$s AS cursor_id
                    FROM %3$s
                    WHERE %4$s
                      AND (? = false OR (%2$s, %1$s) < (?::timestamptz, ?::uuid))
                    ORDER BY %2$s DESC, %1$s DESC
                    LIMIT ?
                    """.formatted(idColumn, timestampColumn, table, predicate);
            var candidates = new ArrayList<FixtureRow>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBoolean(1, cursorId != null);
                if (cursorId == null) {
                    statement.setNull(2, Types.TIMESTAMP_WITH_TIMEZONE);
                    statement.setNull(3, Types.OTHER);
                } else {
                    statement.setObject(2, cursorTimestamp);
                    statement.setObject(3, cursorId);
                }
                statement.setInt(4, 31);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        candidates.add(new FixtureRow(
                                rows.getString(1),
                                rows.getObject(2, OffsetDateTime.class),
                                rows.getObject(3, UUID.class)));
                    }
                }
            }

            hasMore = candidates.size() > 30;
            var pageRows = hasMore ? candidates.subList(0, 30) : candidates;
            pageSizes.add(pageRows.size());
            hasMoreByPage.add(hasMore);
            for (var row : pageRows) pagedIds.add(row.id());
            if (hasMore) {
                var lastVisible = pageRows.getLast();
                cursorTimestamp = lastVisible.timestamp();
                cursorId = lastVisible.cursorId();
            }
        } while (hasMore);

        assertThat(pageSizes).containsExactly(30, 30, 5);
        assertThat(hasMoreByPage).containsExactly(true, true, false);
        assertThat(pagedIds).hasSize(65).doesNotHaveDuplicates();
        assertThat(pagedIds).containsExactlyElementsOf(completeOrderedIds);
    }

    private List<String> readIds(java.sql.Connection connection, String sql) throws SQLException {
        var ids = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) ids.add(rows.getString(1));
        }
        return ids;
    }

    private record FixtureRow(String id, OffsetDateTime timestamp, UUID cursorId) { }

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

    private long longValue(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
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

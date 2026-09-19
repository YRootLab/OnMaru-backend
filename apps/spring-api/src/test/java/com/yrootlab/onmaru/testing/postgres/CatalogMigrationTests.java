package com.yrootlab.onmaru.testing.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogMigrationTests {

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
    void migratesCatalogIdentityRevisionAndSpatialSchema() throws Exception {
        resetAndMigrate();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'onmaru'
                      AND table_name IN (
                        'catalog_regions',
                        'catalog_dataset_revisions',
                        'catalog_place_identity',
                        'catalog_place_sources',
                        'catalog_place_versions'
                      )
                    """)).isEqualTo(5);
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM pg_indexes
                    WHERE schemaname = 'onmaru'
                      AND indexname = 'catalog_place_versions_location_gix'
                      AND indexdef ILIKE '%gist%'
                    """)).isEqualTo(1);
        }
    }

    @Test
    void blocksDuplicateSourceWithinProviderButSeparatesProviderCollisions() throws Exception {
        resetAndMigrate();
        var firstPlace = UUID.randomUUID();
        var secondPlace = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("INSERT INTO onmaru.catalog_place_identity (id, created_at) VALUES "
                    + "('" + firstPlace + "', '" + Instant.parse("2026-09-15T00:00:00Z") + "')");
            statement.execute("INSERT INTO onmaru.catalog_place_identity (id, created_at) VALUES "
                    + "('" + secondPlace + "', '" + Instant.parse("2026-09-15T00:00:01Z") + "')");

            statement.execute("""
                    INSERT INTO onmaru.catalog_place_sources (
                        id, place_id, provider, dataset, external_id, language, fetched_at, payload_hash
                    ) VALUES (
                        gen_random_uuid(), '%s', 'KTO', 'korean-tour', '100', 'ko',
                        '2026-09-15T00:00:00Z', 'hash-1'
                    )
                    """.formatted(firstPlace));

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO onmaru.catalog_place_sources (
                        id, place_id, provider, dataset, external_id, language, fetched_at, payload_hash
                    ) VALUES (
                        gen_random_uuid(), '%s', 'KTO', 'korean-tour', '100', 'ko',
                        '2026-09-15T00:00:00Z', 'hash-duplicate'
                    )
                    """.formatted(secondPlace)))
                    .hasMessageContaining("catalog_place_sources_provider_dataset_external_id_language_uq");

            statement.execute("""
                    INSERT INTO onmaru.catalog_place_sources (
                        id, place_id, provider, dataset, external_id, language, fetched_at, payload_hash
                    ) VALUES (
                        gen_random_uuid(), '%s', 'ODII', 'audio-story', '100', 'ko',
                        '2026-09-15T00:00:00Z', 'hash-provider-collision'
                    )
                    """.formatted(secondPlace));

            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.catalog_place_sources"))
                    .isEqualTo(2);
        }
    }

    @Test
    void enforcesRevisionForeignKeysAndStoresGeographyPoints() throws Exception {
        resetAndMigrate();
        var revisionId = UUID.randomUUID();
        var placeId = UUID.randomUUID();
        var sourceId = UUID.randomUUID();
        var regionId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.catalog_regions (id, code, name, level, active)
                    VALUES ('%s', 'KR-11', '서울', 'SIDO', true)
                    """.formatted(regionId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_dataset_revisions (
                        id, dataset, status, source_observed_at, fetched_at
                    ) VALUES ('%s', 'korean-tour', 'STAGING', '2026-09-15T00:00:00Z', '2026-09-15T00:01:00Z')
                    """.formatted(revisionId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_place_identity (id, created_at)
                    VALUES ('%s', '2026-09-15T00:00:00Z')
                    """.formatted(placeId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_place_sources (
                        id, place_id, provider, dataset, external_id, language, fetched_at, payload_hash
                    ) VALUES (
                        '%s', '%s', 'KTO', 'korean-tour', '100', 'ko',
                        '2026-09-15T00:00:00Z', 'hash-1'
                    )
                    """.formatted(sourceId, placeId));

            statement.execute("""
                    INSERT INTO onmaru.catalog_place_versions (
                        revision_id, place_id, source_ref_id, region_id, name, category, address,
                        location, overview, visit_review_eligible, status, normalized_hash
                    ) VALUES (
                        '%s', '%s', '%s', '%s', '전주 한옥마을', 'HANOK_VILLAGE', '전북 전주시',
                        ST_SetSRID(ST_MakePoint(127.153, 35.815), 4326)::geography,
                        'overview', true, 'ACTIVE', 'normalized-hash-1'
                    )
                    """.formatted(revisionId, placeId, sourceId, regionId));

            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM onmaru.catalog_place_versions
                    WHERE ST_DWithin(
                        location,
                        ST_SetSRID(ST_MakePoint(127.153, 35.815), 4326)::geography,
                        1
                    )
                    """)).isEqualTo(1);

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO onmaru.catalog_place_versions (
                        revision_id, place_id, source_ref_id, name, category,
                        visit_review_eligible, status, normalized_hash
                    ) VALUES (
                        gen_random_uuid(), '%s', '%s', '없는 revision', 'HANOK_VILLAGE',
                        true, 'ACTIVE', 'normalized-hash-invalid'
                    )
                    """.formatted(placeId, sourceId)))
                    .hasMessageContaining("catalog_place_versions_revision_id_fkey");
        }
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

    private static int countRows(java.sql.Statement statement, String sql) throws Exception {
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

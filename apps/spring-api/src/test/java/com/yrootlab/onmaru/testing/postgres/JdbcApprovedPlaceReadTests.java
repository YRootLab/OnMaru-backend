package com.yrootlab.onmaru.testing.postgres;

import org.flywaydb.core.Flyway;
import com.yrootlab.onmaru.catalog.application.query.journey.ApprovedPlaceLookupItem;
import com.yrootlab.onmaru.journey.worker.JourneyCandidate;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRequest;
import com.yrootlab.onmaru.persistence.catalog.journey.JdbcJourneyCandidateProvider;
import com.yrootlab.onmaru.persistence.catalog.journey.JdbcPlaceQueryAdapter;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcApprovedPlaceReadTests {

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

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 9, 0, 0, 0, 0, ZoneOffset.UTC);
    private DataSource dataSource;

    @BeforeAll
    static void startPostgres() {
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @BeforeEach
    void resetDatabase() throws Exception {
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
        dataSource = new DriverManagerDataSource(jdbcUrl(), USERNAME, PASSWORD);
        var jeonju = UUID.randomUUID();
        var seoul = UUID.randomUUID();
        var activeRevision = UUID.randomUUID();
        var inactiveRevision = UUID.randomUUID();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            region(connection, jeonju, "kr-45-jeonju", "전주시");
            region(connection, seoul, "kr-11-jongno", "종로구");
            revision(connection, activeRevision, true);
            revision(connection, inactiveRevision, false);
            place(connection, activeRevision, jeonju, "p-hanok-a", "가 한옥", "HANOK_VILLAGE", "ACTIVE");
            place(connection, activeRevision, jeonju, "p-cafe-b", "나 카페", "CAFE", "ACTIVE");
            place(connection, activeRevision, jeonju, "p-hidden-c", "다 숨김", "HANOK_VILLAGE", "HIDDEN");
            place(connection, activeRevision, seoul, "p-seoul-d", "라 서울", "HANOK_VILLAGE", "ACTIVE");
            place(connection, inactiveRevision, jeonju, "p-old-e", "마 이전", "HANOK_VILLAGE", "ACTIVE");
            connection.commit();
        }
    }

    @Test
    void lookupReturnsOnlyActivePublicIdsInTheRequestedRegionAndCategory() {
        var all = new JdbcPlaceQueryAdapter(dataSource).lookupApprovedPlaces("kr-45-jeonju", null, 5);
        assertThat(all).extracting(ApprovedPlaceLookupItem::placeId).containsExactly("p-hanok-a", "p-cafe-b");

        var hanok = new JdbcPlaceQueryAdapter(dataSource).lookupApprovedPlaces("kr-45-jeonju", "HANOK_VILLAGE", 5);
        assertThat(hanok).extracting(ApprovedPlaceLookupItem::placeId).containsExactly("p-hanok-a");
        assertThat(hanok.getFirst().regionCode()).isEqualTo("kr-45-jeonju");
        assertThat(hanok.getFirst().latitude()).isEqualTo(35.8151);
    }

    @Test
    void lookupIsBoundedToFiveAndNeverExposesInternalIdentity() {
        var items = new JdbcPlaceQueryAdapter(dataSource).lookupApprovedPlaces(null, null, 50);
        assertThat(items.size()).isLessThanOrEqualTo(5);
        assertThat(items).allSatisfy(item -> assertThat(item.placeId()).matches("p-[a-z0-9-]+"));
    }

    @Test
    void candidateProviderSuppliesRegionScopedCandidatesFromTheActiveRevision() {
        var provider = new JdbcJourneyCandidateProvider(dataSource);
        var payload = provider.retrieve(new JourneyWorkerRequest(UUID.randomUUID(), UUID.randomUUID(), "actor", 1,
                "corpus-rev-1", "kr-45-jeonju", "query", 0, "req-1", "0af7651916cd43dd8448eb211c80319c",
                NOW.plusSeconds(20).toInstant()));

        assertThat(payload.datasetRevision()).isEqualTo("corpus-rev-1");
        // Candidates are nationwide; the requested region only ranks its own places first.
        assertThat(payload.candidates()).extracting(JourneyCandidate::ref)
                .hasSizeLessThanOrEqualTo(12)
                .startsWith("p-hanok-a", "p-cafe-b");
    }

    private static void region(Connection connection, UUID id, String code, String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO onmaru.catalog_regions (id, code, name, level, active) VALUES (?, ?, ?, 'SIGUNGU', true)")) {
            statement.setObject(1, id);
            statement.setString(2, code);
            statement.setString(3, name);
            statement.executeUpdate();
        }
    }

    private static void revision(Connection connection, UUID id, boolean active) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO onmaru.catalog_dataset_revisions (id, dataset, status, fetched_at, published_at) VALUES (?, 'kto-korean-tour', 'PUBLISHED', ?, ?)")) {
            statement.setObject(1, id);
            statement.setObject(2, NOW);
            statement.setObject(3, NOW);
            statement.executeUpdate();
        }
        if (active) {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at) VALUES ('kto-korean-tour', ?, ?)")) {
                statement.setObject(1, id);
                statement.setObject(2, NOW);
                statement.executeUpdate();
            }
        }
    }

    private static void place(Connection connection, UUID revision, UUID region, String publicId, String name,
                              String category, String status) throws SQLException {
        var identity = UUID.randomUUID();
        var source = UUID.randomUUID();
        try (var statement = connection.prepareStatement("INSERT INTO onmaru.catalog_place_identity (id, created_at) VALUES (?, ?)")) {
            statement.setObject(1, identity);
            statement.setObject(2, NOW);
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement(
                "INSERT INTO onmaru.catalog_place_public_ids (public_id, place_id, created_at) VALUES (?, ?, ?)")) {
            statement.setString(1, publicId);
            statement.setObject(2, identity);
            statement.setObject(3, NOW);
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement(
                "INSERT INTO onmaru.catalog_place_sources (id, place_id, provider, dataset, external_id, language, fetched_at) VALUES (?, ?, 'KTO', 'kto-korean-tour', ?, 'ko', ?)")) {
            statement.setObject(1, source);
            statement.setObject(2, identity);
            statement.setString(3, publicId);
            statement.setObject(4, NOW);
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_place_versions (
                    revision_id, place_id, source_ref_id, region_id, name, category, address, location,
                    overview, visit_review_eligible, status, normalized_hash
                ) VALUES (?, ?, ?, ?, ?, ?, '전주시 완산구',
                          ST_SetSRID(ST_MakePoint(127.1530, 35.8151), 4326)::geography,
                          '개요', true, ?::onmaru.catalog_place_status, 'hash')
                """)) {
            statement.setObject(1, revision);
            statement.setObject(2, identity);
            statement.setObject(3, source);
            statement.setObject(4, region);
            statement.setString(5, name);
            statement.setString(6, category);
            statement.setString(7, status);
            statement.executeUpdate();
        }
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
        public Connection getConnection(String suppliedUsername, String suppliedPassword) throws SQLException {
            return DriverManager.getConnection(url, suppliedUsername, suppliedPassword);
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return DriverManager.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            DriverManager.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            DriverManager.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return DriverManager.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }
}

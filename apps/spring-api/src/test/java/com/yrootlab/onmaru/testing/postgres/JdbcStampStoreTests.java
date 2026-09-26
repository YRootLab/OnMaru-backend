package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.persistence.jdbc.JdbcTransactionRunner;
import com.yrootlab.onmaru.persistence.stamp.JdbcCheckInPlaceLookup;
import com.yrootlab.onmaru.persistence.stamp.JdbcStampStore;
import com.yrootlab.onmaru.stamp.CheckInCommand;
import com.yrootlab.onmaru.stamp.CheckInPlaceNotFoundException;
import com.yrootlab.onmaru.stamp.OutsideCheckInRadiusException;
import com.yrootlab.onmaru.stamp.StampService;
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
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcStampStoreTests {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private static final Instant NOW = Instant.parse("2026-09-26T02:30:00Z");
    private DataSource dataSource;
    private UUID memberId;

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
        memberId = UUID.randomUUID();
        seedMember(memberId);
    }

    @Test
    void verifiesActiveHanokWithPostgisAndPersistsOneCheckInAndAward() throws Exception {
        seedPlace("p-bukchon-house", "kr-11-jongno", "HANOK", 37.5826, 126.9831, true);
        var transactions = new JdbcTransactionRunner(dataSource);
        var service = new StampService(
                new JdbcCheckInPlaceLookup(dataSource, transactions),
                new JdbcStampStore(dataSource, transactions),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var first = service.checkIn(memberId,
                new CheckInCommand("p-bukchon-house", 37.5826, 126.9831, 18.4));
        var repeated = service.checkIn(memberId,
                new CheckInCommand("p-bukchon-house", 37.5826, 126.9831, 18.4));

        assertThat(first.checkIn().distanceMeters()).isZero();
        assertThat(first.newAwards()).extracting("code").containsExactly("stamp_bukchon");
        assertThat(repeated.checkIn().id()).isEqualTo(first.checkIn().id());
        assertThat(repeated.checkIn().alreadyCheckedIn()).isTrue();
        assertThat(repeated.newAwards()).isEmpty();
        assertThat(service.book(memberId).summary().collectedCount()).isEqualTo(1);
        assertThat(count("onmaru.stamp_check_ins")).isEqualTo(1);
        assertThat(count("onmaru.stamp_awards")).isEqualTo(1);
        assertThat(rawCoordinateColumns()).isZero();
    }

    @Test
    void rejectsFarOrIneligibleCatalogPlacesWithoutWritingHistory() throws Exception {
        seedPlace("p-far-hanok", "kr-11-jongno", "HANOK", 37.5826, 126.9831, true);
        seedPlace("p-general-place", "kr-11-jongno", "HISTORIC_SITE", 37.5826, 126.9831, true);
        seedPlace("p-hidden-hanok", "kr-11-jongno", "HANOK", 37.5826, 126.9831, false);
        var transactions = new JdbcTransactionRunner(dataSource);
        var service = new StampService(
                new JdbcCheckInPlaceLookup(dataSource, transactions),
                new JdbcStampStore(dataSource, transactions),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.checkIn(memberId,
                new CheckInCommand("p-far-hanok", 35.0, 127.0, 10)))
                .isInstanceOf(OutsideCheckInRadiusException.class);
        assertThatThrownBy(() -> service.checkIn(memberId,
                new CheckInCommand("p-general-place", 37.5826, 126.9831, 10)))
                .isInstanceOf(CheckInPlaceNotFoundException.class);
        assertThatThrownBy(() -> service.checkIn(memberId,
                new CheckInCommand("p-hidden-hanok", 37.5826, 126.9831, 10)))
                .isInstanceOf(CheckInPlaceNotFoundException.class);
        assertThat(count("onmaru.stamp_check_ins")).isZero();
    }

    private void seedMember(UUID id) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO onmaru.identity_members (id, status, created_at) VALUES (?, 'ACTIVE', ?)")) {
            statement.setObject(1, id);
            statement.setObject(2, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private void seedPlace(
            String publicId, String regionCode, String category, double latitude, double longitude, boolean active)
            throws Exception {
        var placeId = UUID.randomUUID();
        var regionId = UUID.randomUUID();
        var revisionId = UUID.randomUUID();
        var sourceId = UUID.randomUUID();
        var now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var identity = connection.prepareStatement(
                    "INSERT INTO onmaru.catalog_place_identity (id, created_at) VALUES (?, ?)");
                 var mapping = connection.prepareStatement(
                         "INSERT INTO onmaru.catalog_place_public_ids (public_id, place_id, created_at) VALUES (?, ?, ?)");
                 var region = connection.prepareStatement(
                         "INSERT INTO onmaru.catalog_regions (id, code, name, level, active) VALUES (?, ?, ?, 'SIGUNGU', true)");
                 var revision = connection.prepareStatement(
                         "INSERT INTO onmaru.catalog_dataset_revisions (id, dataset, status, fetched_at, published_at) VALUES (?, ?, 'PUBLISHED', ?, ?)");
                 var activeRevision = connection.prepareStatement(
                         "INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at) VALUES (?, ?, ?)");
                 var source = connection.prepareStatement("""
                         INSERT INTO onmaru.catalog_place_sources
                             (id, place_id, provider, dataset, external_id, language, fetched_at)
                         VALUES (?, ?, 'TEST', ?, ?, 'ko', ?)
                         """);
                 var version = connection.prepareStatement("""
                         INSERT INTO onmaru.catalog_place_versions
                             (revision_id, place_id, source_ref_id, region_id, name, category, location,
                              visit_review_eligible, status, normalized_hash)
                         VALUES (?, ?, ?, ?, ?, ?,
                                 ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                                 true, ?::onmaru.catalog_place_status, ?)
                         """)) {
                var dataset = "TEST-" + publicId;
                identity.setObject(1, placeId);
                identity.setObject(2, now);
                identity.executeUpdate();
                mapping.setString(1, publicId);
                mapping.setObject(2, placeId);
                mapping.setObject(3, now);
                mapping.executeUpdate();
                region.setObject(1, regionId);
                region.setString(2, regionCode + "-" + publicId.substring(2));
                region.setString(3, regionCode);
                region.executeUpdate();
                revision.setObject(1, revisionId);
                revision.setString(2, dataset);
                revision.setObject(3, now);
                revision.setObject(4, now);
                revision.executeUpdate();
                activeRevision.setString(1, dataset);
                activeRevision.setObject(2, revisionId);
                activeRevision.setObject(3, now);
                activeRevision.executeUpdate();
                source.setObject(1, sourceId);
                source.setObject(2, placeId);
                source.setString(3, dataset);
                source.setString(4, publicId);
                source.setObject(5, now);
                source.executeUpdate();
                version.setObject(1, revisionId);
                version.setObject(2, placeId);
                version.setObject(3, sourceId);
                version.setObject(4, regionId);
                version.setString(5, publicId);
                version.setString(6, category);
                version.setDouble(7, longitude);
                version.setDouble(8, latitude);
                version.setString(9, active ? "ACTIVE" : "HIDDEN");
                version.setString(10, "hash-" + publicId);
                version.executeUpdate();
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private long count(String relation) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM " + relation)) {
            result.next();
            return result.getLong(1);
        }
    }

    private long rawCoordinateColumns() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT count(*)
                     FROM information_schema.columns
                     WHERE table_schema = 'onmaru'
                       AND table_name = 'stamp_check_ins'
                       AND column_name IN ('latitude', 'longitude')
                     """);
             var result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
                + "/onmaru_test";
    }
}

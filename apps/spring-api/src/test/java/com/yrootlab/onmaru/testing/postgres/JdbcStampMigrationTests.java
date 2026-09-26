package com.yrootlab.onmaru.testing.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcStampMigrationTests {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void migrateFromZero() throws Exception {
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
    void seedsStampCatalogAndProtectsMemberRelationships() throws Exception {
        var memberId = UUID.randomUUID();
        var placeId = UUID.randomUUID();
        var checkInId = UUID.randomUUID();
        var now = OffsetDateTime.of(2026, 9, 26, 2, 30, 0, 0, ZoneOffset.UTC);

        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            assertThat(count(connection, "onmaru.stamp_definitions")).isEqualTo(12);
            assertThat(count(connection, "onmaru.stamp_region_rules")).isEqualTo(10);

            try (var member = connection.prepareStatement(
                    "INSERT INTO onmaru.identity_members (id, status, created_at) VALUES (?, 'ACTIVE', ?)");
                 var place = connection.prepareStatement(
                         "INSERT INTO onmaru.catalog_place_identity (id, created_at) VALUES (?, ?)");
                 var checkIn = connection.prepareStatement("""
                         INSERT INTO onmaru.stamp_check_ins
                             (id, member_id, place_id, public_place_id, region_code, checked_in_at,
                              check_in_bucket, distance_meters, accuracy_meters)
                         VALUES (?, ?, ?, 'p-test-hanok', 'kr-11-jongno', ?, ?, 50, 20)
                         """);
                 var award = connection.prepareStatement("""
                         INSERT INTO onmaru.stamp_awards
                             (id, member_id, stamp_code, trigger_check_in_id, awarded_at)
                         VALUES (?, ?, 'stamp_bukchon', ?, ?)
                         """)) {
                member.setObject(1, memberId);
                member.setObject(2, now);
                member.executeUpdate();
                place.setObject(1, placeId);
                place.setObject(2, now);
                place.executeUpdate();
                checkIn.setObject(1, checkInId);
                checkIn.setObject(2, memberId);
                checkIn.setObject(3, placeId);
                checkIn.setObject(4, now);
                checkIn.setObject(5, now);
                checkIn.executeUpdate();
                award.setObject(1, UUID.randomUUID());
                award.setObject(2, memberId);
                award.setObject(3, checkInId);
                award.setObject(4, now);
                award.executeUpdate();
            }

            assertThatThrownBy(() -> duplicateCheckIn(connection, memberId, placeId, now))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> duplicateAward(connection, memberId, checkInId, now))
                    .isInstanceOf(SQLException.class);

            try (var delete = connection.prepareStatement("DELETE FROM onmaru.identity_members WHERE id = ?")) {
                delete.setObject(1, memberId);
                assertThat(delete.executeUpdate()).isEqualTo(1);
            }
            assertThat(count(connection, "onmaru.stamp_check_ins")).isZero();
            assertThat(count(connection, "onmaru.stamp_awards")).isZero();
        }
    }

    private void duplicateCheckIn(java.sql.Connection connection, UUID memberId, UUID placeId, OffsetDateTime now)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.stamp_check_ins
                    (id, member_id, place_id, public_place_id, region_code, checked_in_at,
                     check_in_bucket, distance_meters, accuracy_meters)
                VALUES (?, ?, ?, 'p-test-hanok', 'kr-11-jongno', ?, ?, 50, 20)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, memberId);
            statement.setObject(3, placeId);
            statement.setObject(4, now);
            statement.setObject(5, now);
            statement.executeUpdate();
        }
    }

    private void duplicateAward(java.sql.Connection connection, UUID memberId, UUID checkInId, OffsetDateTime now)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.stamp_awards
                    (id, member_id, stamp_code, trigger_check_in_id, awarded_at)
                VALUES (?, ?, 'stamp_bukchon', ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, memberId);
            statement.setObject(3, checkInId);
            statement.setObject(4, now);
            statement.executeUpdate();
        }
    }

    private long count(java.sql.Connection connection, String relation) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM " + relation)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
                + "/onmaru_test";
    }
}

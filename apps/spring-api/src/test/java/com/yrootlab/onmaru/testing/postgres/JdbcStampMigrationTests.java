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
            assertThatThrownBy(() -> impossibleSuccessfulDistance(connection, memberId, placeId, now))
                    .isInstanceOf(SQLException.class);

            try (var delete = connection.prepareStatement("DELETE FROM onmaru.identity_members WHERE id = ?")) {
                delete.setObject(1, memberId);
                assertThat(delete.executeUpdate()).isEqualTo(1);
            }
            assertThat(count(connection, "onmaru.stamp_check_ins")).isZero();
            assertThat(count(connection, "onmaru.stamp_awards")).isZero();
        }
    }

    @Test
    void defaultsToPrivateProfileAndDeletesItWithMember() throws Exception {
        var memberId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            seedMember(connection, memberId);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO onmaru.stamp_ranking_profiles (member_id, created_at, updated_at)
                        VALUES ('%s', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """.formatted(memberId));
                try (var result = statement.executeQuery("""
                        SELECT participating, ranking_public_id, public_nickname, nickname_normalized,
                               nickname_type, consented_at
                        FROM onmaru.stamp_ranking_profiles WHERE member_id = '%s'
                        """.formatted(memberId))) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean("participating")).isFalse();
                    assertThat(result.getObject("ranking_public_id")).isNull();
                    assertThat(result.getString("public_nickname")).isNull();
                    assertThat(result.getString("nickname_normalized")).isNull();
                    assertThat(result.getString("nickname_type")).isNull();
                    assertThat(result.getObject("consented_at")).isNull();
                }
                statement.executeUpdate("DELETE FROM onmaru.identity_members WHERE id = '%s'".formatted(memberId));
            }
            assertThat(count(connection, "onmaru.stamp_ranking_profiles")).isZero();
        }
    }

    @Test
    void requiresCompleteGeneratedIdentityOnlyDuringParticipation() throws Exception {
        var memberId = UUID.randomUUID();
        var publicId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            seedMember(connection, memberId);
            assertThatThrownBy(() -> insertProfile(connection, memberId, publicId,
                    "한옥여행", "한옥여행", "GENERATED", false, true))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("stamp_ranking_profiles_state_ck");
            assertThatThrownBy(() -> insertProfile(connection, memberId, null,
                    "한옥여행", "한옥여행", "GENERATED", true, true))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("stamp_ranking_profiles_state_ck");
            assertThatThrownBy(() -> insertProfile(connection, memberId, publicId,
                    "한옥여행", "한옥여행", "CUSTOM", true, true))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("stamp_ranking_profiles_state_ck");
            assertThatThrownBy(() -> insertProfile(connection, memberId, publicId,
                    "한옥여행", "한옥여행", null, true, true))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("stamp_ranking_profiles_state_ck");
            assertThatThrownBy(() -> insertProfile(connection, memberId, publicId,
                    " 한옥여행", "한옥여행", "GENERATED", true, true))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("stamp_ranking_profiles_nickname_ck");
            assertThatThrownBy(() -> insertProfile(connection, memberId, publicId,
                    "한", "한", "GENERATED", true, true))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("stamp_ranking_profiles_nickname_ck");
            assertThatThrownBy(() -> insertProfile(connection, memberId, publicId,
                    "한옥여행", "한옥여행", "GENERATED", true, false))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("stamp_ranking_profiles_state_ck");
            insertProfile(connection, memberId, publicId, "한옥여행", "한옥여행", "GENERATED", true, true);
        }
    }

    @Test
    void keepsParticipatingPublicIdsAndNormalizedNicknamesUnique() throws Exception {
        var firstMember = UUID.randomUUID();
        var secondMember = UUID.randomUUID();
        var thirdMember = UUID.randomUUID();
        var publicId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            seedMember(connection, firstMember);
            seedMember(connection, secondMember);
            seedMember(connection, thirdMember);
            insertProfile(connection, firstMember, publicId, "한옥여행", "한옥여행", "GENERATED", true, true);
            assertThatThrownBy(() -> insertProfile(connection, secondMember, publicId,
                    "기와산책", "기와산책", "GENERATED", true, true))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("stamp_ranking_profiles_public_id_uq");
            assertThatThrownBy(() -> insertProfile(connection, secondMember, UUID.randomUUID(),
                    "한옥여행", "한옥여행", "GENERATED", true, true))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("stamp_ranking_profiles_nickname_uq");
            insertProfile(connection, secondMember, UUID.randomUUID(),
                    "기와산책", "기와산책", "GENERATED", true, true);
            insertProfile(connection, thirdMember, null, null, null, null, false, false);
            assertThat(count(connection, "onmaru.stamp_ranking_profiles")).isEqualTo(3);
        }
    }

    private void seedMember(java.sql.Connection connection, UUID memberId) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO onmaru.identity_members (id, status, created_at)
                    VALUES ('%s', 'ACTIVE', CURRENT_TIMESTAMP)
                    """.formatted(memberId));
        }
    }

    private void insertProfile(java.sql.Connection connection, UUID memberId, UUID publicId,
            String nickname, String normalizedNickname, String nicknameType,
            boolean participating, boolean consented) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.stamp_ranking_profiles (
                    member_id, ranking_public_id, public_nickname, nickname_normalized,
                    nickname_type, participating, consented_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)) {
            statement.setObject(1, memberId);
            statement.setObject(2, publicId);
            statement.setString(3, nickname);
            statement.setString(4, normalizedNickname);
            statement.setString(5, nicknameType);
            statement.setBoolean(6, participating);
            statement.setBoolean(7, consented);
            statement.executeUpdate();
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

    private void impossibleSuccessfulDistance(
            java.sql.Connection connection, UUID memberId, UUID placeId, OffsetDateTime now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.stamp_check_ins
                    (id, member_id, place_id, public_place_id, region_code, checked_in_at,
                     check_in_bucket, distance_meters, accuracy_meters)
                VALUES (?, ?, ?, 'p-invalid-distance', 'kr-11-jongno', ?, ?, 300, 1)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, memberId);
            statement.setObject(3, placeId);
            statement.setObject(4, now.plusMinutes(15));
            statement.setObject(5, now.plusMinutes(15));
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

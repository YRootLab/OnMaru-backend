package com.yrootlab.onmaru.testing.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapCollabV058MigrationConstraintsTests {

    private static final GenericContainer<?> DB = new GenericContainer<>(DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private DriverManagerDataSource dataSource;

    @BeforeAll
    static void start() {
        DB.start();
    }

    @AfterAll
    static void stop() {
        DB.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        String url = jdbcUrl();
        try (Connection conn = DriverManager.getConnection(url, "onmaru_test", "onmaru_test")) {
            PostgresTestDatabase.reset(conn);
        }
        Flyway.configure()
                .dataSource(url, "onmaru_test", "onmaru_test")
                .locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(url, "onmaru_test", "onmaru_test");
    }

    @Test
    void reservationForV058Exists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT reserved_for, issue_number, description
                     FROM onmaru_registry.migration_version_reservations
                     WHERE version = '058'
                     """)) {
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("reserved_for")).isEqualTo("MAP_COLLAB");
                assertThat(rs.getInt("issue_number")).isEqualTo(825);
            }
        }
    }

    @Test
    void mapRoomsConstraintsWork() throws Exception {
        UUID creatorId = insertMember();
        Instant now = Instant.now();

        // 1. Valid room insert
        UUID roomId = UUID.randomUUID();
        insertRoom(roomId, creatorId, "ACTIVE", now, now.plus(72, ChronoUnit.HOURS), null, null, 0L);

        // 2. Expiry check constraint: expires_at must be > created_at
        UUID invalidRoom1 = UUID.randomUUID();
        assertThatThrownBy(() -> insertRoom(invalidRoom1, creatorId, "ACTIVE", now, now.minusSeconds(1), null, null, 0L))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("map_rooms_expiry_ck");

        // 3. Closed check constraint: (status = 'CLOSED') = (closed_at IS NOT NULL)
        UUID invalidRoom2 = UUID.randomUUID();
        assertThatThrownBy(() -> insertRoom(invalidRoom2, creatorId, "CLOSED", now, now.plus(72, ChronoUnit.HOURS), null, null, 0L))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("map_rooms_closed_ck");

        UUID invalidRoom3 = UUID.randomUUID();
        assertThatThrownBy(() -> insertRoom(invalidRoom3, creatorId, "ACTIVE", now, now.plus(72, ChronoUnit.HOURS), now, null, 0L))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("map_rooms_closed_ck");

        // 4. Closed valid
        UUID closedRoom = UUID.randomUUID();
        insertRoom(closedRoom, creatorId, "CLOSED", now, now.plus(72, ChronoUnit.HOURS), now, creatorId, 0L);
    }

    @Test
    void mapRoomInvitesConstraintsWork() throws Exception {
        UUID creatorId = insertMember();
        Instant now = Instant.now();
        UUID roomId = UUID.randomUUID();
        insertRoom(roomId, creatorId, "ACTIVE", now, now.plus(72, ChronoUnit.HOURS), null, null, 0L);

        String validHash1 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        insertInvite(UUID.randomUUID(), roomId, validHash1, creatorId, now, now.plus(72, ChronoUnit.HOURS), null);

        // Duplicate hash fails UNIQUE
        assertThatThrownBy(() -> insertInvite(UUID.randomUUID(), roomId, validHash1, creatorId, now, now.plus(72, ChronoUnit.HOURS), null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("map_room_invites_token_hash_uq");

        // Invalid hash format check
        assertThatThrownBy(() -> insertInvite(UUID.randomUUID(), roomId, "not-a-valid-sha256-hex", creatorId, now, now.plus(72, ChronoUnit.HOURS), null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("map_room_invites_token_hash_format_ck");
    }

    @Test
    void mapRoomPinsUniqueActivePlaceConstraint() throws Exception {
        UUID creatorId = insertMember();
        Instant now = Instant.now();
        UUID roomId = UUID.randomUUID();
        insertRoom(roomId, creatorId, "ACTIVE", now, now.plus(72, ChronoUnit.HOURS), null, null, 0L);

        String placePublicId = insertPlacePublicId("p-test-place-1");

        UUID pinId1 = UUID.randomUUID();
        insertPin(roomId, pinId1, placePublicId, "테스트 장소 1", 37.5, 127.0, creatorId, now, 1L, null, null, null);

        // Same place in same room active pin fails (D1)
        UUID pinId2 = UUID.randomUUID();
        assertThatThrownBy(() -> insertPin(roomId, pinId2, placePublicId, "테스트 장소 1 다시", 37.5, 127.0, creatorId, now, 2L, null, null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("map_room_pins_active_place_uq");

        // Once pinId1 is removed, same place can be added again
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE onmaru.map_room_pins
                     SET removed_by = ?, removed_at = ?, removed_seq = ?
                     WHERE room_id = ? AND pin_id = ?
                     """)) {
            ps.setObject(1, creatorId);
            ps.setTimestamp(2, Timestamp.from(now.plusSeconds(10)));
            ps.setLong(3, 2L);
            ps.setObject(4, roomId);
            ps.setObject(5, pinId1);
            ps.executeUpdate();
        }

        // Now pinId2 with same place succeeds!
        insertPin(roomId, pinId2, placePublicId, "테스트 장소 1 다시", 37.5, 127.0, creatorId, now.plusSeconds(20), 3L, null, null, null);
    }

    @Test
    void mapRoomEventsUniqueClientOpId() throws Exception {
        UUID creatorId = insertMember();
        Instant now = Instant.now();
        UUID roomId = UUID.randomUUID();
        insertRoom(roomId, creatorId, "ACTIVE", now, now.plus(72, ChronoUnit.HOURS), null, null, 0L);

        UUID clientOpId = UUID.randomUUID();
        insertEvent(roomId, 1L, "PIN_ADDED", creatorId, clientOpId, "{}");

        // Same opId on same room fails unique constraint
        assertThatThrownBy(() -> insertEvent(roomId, 2L, "PIN_ADDED", creatorId, clientOpId, "{}"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("map_room_events_client_op_id_uq");
    }

    private UUID insertMember() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO onmaru.identity_members (id, status, created_at) VALUES (?, 'ACTIVE', now())")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
        return id;
    }

    private String insertPlacePublicId(String publicId) throws SQLException {
        UUID placeId = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO onmaru.catalog_place_identity (id, created_at) VALUES (?, now())")) {
                ps.setObject(1, placeId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO onmaru.catalog_place_public_ids (public_id, place_id, created_at) VALUES (?, ?, now())")) {
                ps.setString(1, publicId);
                ps.setObject(2, placeId);
                ps.executeUpdate();
            }
        }
        return publicId;
    }

    private void insertRoom(UUID id, UUID createdBy, String status, Instant createdAt, Instant expiresAt, Instant closedAt, UUID closedBy, long lastSeq) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO onmaru.map_rooms (id, created_by, status, created_at, expires_at, closed_at, closed_by, last_event_seq)
                     VALUES (?, ?, ?::onmaru.map_room_status, ?, ?, ?, ?, ?)
                     """)) {
            ps.setObject(1, id);
            ps.setObject(2, createdBy);
            ps.setString(3, status);
            ps.setTimestamp(4, Timestamp.from(createdAt));
            ps.setTimestamp(5, Timestamp.from(expiresAt));
            ps.setTimestamp(6, closedAt != null ? Timestamp.from(closedAt) : null);
            ps.setObject(7, closedBy);
            ps.setLong(8, lastSeq);
            ps.executeUpdate();
        }
    }

    private void insertInvite(UUID id, UUID roomId, String tokenHash, UUID createdBy, Instant createdAt, Instant expiresAt, Instant revokedAt) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO onmaru.map_room_invites (id, room_id, token_hash, created_by, created_at, expires_at, revoked_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setObject(1, id);
            ps.setObject(2, roomId);
            ps.setString(3, tokenHash);
            ps.setObject(4, createdBy);
            ps.setTimestamp(5, Timestamp.from(createdAt));
            ps.setTimestamp(6, Timestamp.from(expiresAt));
            ps.setTimestamp(7, revokedAt != null ? Timestamp.from(revokedAt) : null);
            ps.executeUpdate();
        }
    }

    private void insertPin(UUID roomId, UUID pinId, String placePublicId, String placeName, double lat, double lng, UUID addedBy, Instant addedAt, long addedSeq, UUID removedBy, Instant removedAt, Long removedSeq) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO onmaru.map_room_pins (room_id, pin_id, place_public_id, place_name, lat, lng, added_by, added_at, added_seq, removed_by, removed_at, removed_seq)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setObject(1, roomId);
            ps.setObject(2, pinId);
            ps.setString(3, placePublicId);
            ps.setString(4, placeName);
            ps.setDouble(5, lat);
            ps.setDouble(6, lng);
            ps.setObject(7, addedBy);
            ps.setTimestamp(8, Timestamp.from(addedAt));
            ps.setLong(9, addedSeq);
            ps.setObject(10, removedBy);
            ps.setTimestamp(11, removedAt != null ? Timestamp.from(removedAt) : null);
            ps.setObject(12, removedSeq);
            ps.executeUpdate();
        }
    }

    private void insertEvent(UUID roomId, long seq, String eventType, UUID actorMemberId, UUID clientOpId, String payload) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO onmaru.map_room_events (room_id, seq, event_type, actor_member_id, client_op_id, payload, created_at)
                     VALUES (?, ?, ?::onmaru.map_room_event_type, ?, ?, ?::jsonb, now())
                     """)) {
            ps.setObject(1, roomId);
            ps.setLong(2, seq);
            ps.setString(3, eventType);
            ps.setObject(4, actorMemberId);
            ps.setObject(5, clientOpId);
            ps.setString(6, payload);
            ps.executeUpdate();
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/onmaru_test";
    }
}

package com.yrootlab.onmaru.persistence.mapcollab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class JdbcRoomSessionStore implements RoomSessionStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcRoomSessionStore.class);
    private final DataSource dataSource;

    public JdbcRoomSessionStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    public void insertSession(UUID id, UUID roomId, UUID memberId, String instanceId, Instant connectedAt) {
        String sql = """
                INSERT INTO onmaru.map_room_sessions (id, room_id, member_id, instance_id, connected_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setObject(2, roomId);
            ps.setObject(3, memberId);
            ps.setString(4, instanceId);
            ps.setTimestamp(5, Timestamp.from(connectedAt));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to insert map_room_session: {}", id, e);
        }
    }

    public void updateDisconnected(UUID id, Instant disconnectedAt, String endReason) {
        String sql = """
                UPDATE onmaru.map_room_sessions
                SET disconnected_at = ?, end_reason = ?
                WHERE id = ? AND disconnected_at IS NULL
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(disconnectedAt));
            ps.setString(2, endReason != null ? endReason : "CLIENT_CLOSED");
            ps.setObject(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update map_room_session disconnected: {}", id, e);
        }
    }
}

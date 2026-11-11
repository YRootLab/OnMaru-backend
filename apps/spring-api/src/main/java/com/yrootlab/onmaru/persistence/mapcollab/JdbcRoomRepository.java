package com.yrootlab.onmaru.persistence.mapcollab;

import com.yrootlab.onmaru.mapcollab.domain.Invite;
import com.yrootlab.onmaru.mapcollab.domain.Member;
import com.yrootlab.onmaru.mapcollab.domain.MemberRole;
import com.yrootlab.onmaru.mapcollab.domain.Pin;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;
import com.yrootlab.onmaru.mapcollab.domain.RoomEventType;
import com.yrootlab.onmaru.mapcollab.domain.RoomStatus;
import com.yrootlab.onmaru.mapcollab.domain.UserRoomMembership;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class JdbcRoomRepository implements RoomRepository {

    private final DataSource dataSource;

    public JdbcRoomRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void saveRoomWithInitialMemberAndEvent(Room room, Member initialMember, RoomEvent createEvent) {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 1. insert room
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO onmaru.map_rooms (id, created_by, status, created_at, expires_at, last_event_seq)
                        VALUES (?, ?, ?::onmaru.map_room_status, ?, ?, ?)
                        """)) {
                    ps.setObject(1, room.id());
                    ps.setObject(2, room.createdBy());
                    ps.setString(3, room.status().name());
                    ps.setTimestamp(4, Timestamp.from(room.createdAt()));
                    ps.setTimestamp(5, Timestamp.from(room.expiresAt()));
                    ps.setLong(6, room.lastEventSeq());
                    ps.executeUpdate();
                }

                // 2. insert initial member
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO onmaru.map_room_members (room_id, member_id, role, joined_at)
                        VALUES (?, ?, ?::onmaru.map_room_role, ?)
                        """)) {
                    ps.setObject(1, initialMember.roomId());
                    ps.setObject(2, initialMember.memberId());
                    ps.setString(3, initialMember.role().name());
                    ps.setTimestamp(4, Timestamp.from(initialMember.joinedAt()));
                    ps.executeUpdate();
                }

                // 3. insert room event
                insertEvent(conn, createEvent);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException("Failed to save room with initial member and event", e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database connection failure", e);
        }
    }

    @Override
    public Optional<Room> findRoomById(UUID roomId) {
        String sql = """
                SELECT id, created_by, status, created_at, expires_at, closed_at, closed_by, last_event_seq
                FROM onmaru.map_rooms
                WHERE id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRoom(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find room by id", e);
        }
    }

    @Override
    public boolean isMember(UUID roomId, UUID memberId) {
        String sql = "SELECT EXISTS (SELECT 1 FROM onmaru.map_room_members WHERE room_id = ? AND member_id = ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, roomId);
            ps.setObject(2, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check room membership", e);
        }
    }

    @Override
    public Optional<MemberRole> findMemberRole(UUID roomId, UUID memberId) {
        String sql = "SELECT role FROM onmaru.map_room_members WHERE room_id = ? AND member_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, roomId);
            ps.setObject(2, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(MemberRole.valueOf(rs.getString("role")));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find member role", e);
        }
    }

    @Override
    public List<Member> findMembersByRoomId(UUID roomId) {
        String sql = "SELECT room_id, member_id, role, joined_at FROM onmaru.map_room_members WHERE room_id = ? ORDER BY joined_at ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Member> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new Member(
                            (UUID) rs.getObject("room_id"),
                            (UUID) rs.getObject("member_id"),
                            "",
                            MemberRole.valueOf(rs.getString("role")),
                            rs.getTimestamp("joined_at").toInstant()
                    ));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list members", e);
        }
    }

    @Override
    public void saveInvite(Invite invite, RoomEvent inviteEvent) {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO onmaru.map_room_invites (id, room_id, token_hash, created_by, created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    ps.setObject(1, invite.id());
                    ps.setObject(2, invite.roomId());
                    ps.setString(3, invite.tokenHash());
                    ps.setObject(4, invite.createdBy());
                    ps.setTimestamp(5, Timestamp.from(invite.createdAt()));
                    ps.setTimestamp(6, Timestamp.from(invite.expiresAt()));
                    ps.executeUpdate();
                }

                insertEvent(conn, inviteEvent);
                updateLastEventSeq(conn, invite.roomId(), inviteEvent.seq());

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException("Failed to save invite", e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database connection failure", e);
        }
    }

    @Override
    public Optional<Invite> findInviteByTokenHash(String tokenHash) {
        String sql = """
                SELECT id, room_id, token_hash, created_by, created_at, expires_at, revoked_at
                FROM onmaru.map_room_invites
                WHERE token_hash = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tokenHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapInvite(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find invite by hash", e);
        }
    }

    @Override
    public Optional<Invite> findInviteById(UUID roomId, UUID inviteId) {
        String sql = """
                SELECT id, room_id, token_hash, created_by, created_at, expires_at, revoked_at
                FROM onmaru.map_room_invites
                WHERE room_id = ? AND id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, roomId);
            ps.setObject(2, inviteId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapInvite(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find invite by id", e);
        }
    }

    @Override
    public void revokeInvite(UUID roomId, UUID inviteId, Instant revokedAt, RoomEvent revokeEvent) {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE onmaru.map_room_invites
                        SET revoked_at = ?
                        WHERE room_id = ? AND id = ? AND revoked_at IS NULL
                        """)) {
                    ps.setTimestamp(1, Timestamp.from(revokedAt));
                    ps.setObject(2, roomId);
                    ps.setObject(3, inviteId);
                    ps.executeUpdate();
                }

                insertEvent(conn, revokeEvent);
                updateLastEventSeq(conn, roomId, revokeEvent.seq());

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException("Failed to revoke invite", e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database connection failure", e);
        }
    }

    @Override
    public long countActiveInvites(UUID roomId, Instant now) {
        String sql = """
                SELECT count(*)
                FROM onmaru.map_room_invites
                WHERE room_id = ? AND revoked_at IS NULL AND expires_at > ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, roomId);
            ps.setTimestamp(2, Timestamp.from(now));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count active invites", e);
        }
    }

    @Override
    public void addMember(Member member, RoomEvent joinEvent) {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO onmaru.map_room_members (room_id, member_id, role, joined_at)
                        VALUES (?, ?, ?::onmaru.map_room_role, ?)
                        """)) {
                    ps.setObject(1, member.roomId());
                    ps.setObject(2, member.memberId());
                    ps.setString(3, member.role().name());
                    ps.setTimestamp(4, Timestamp.from(member.joinedAt()));
                    ps.executeUpdate();
                }

                insertEvent(conn, joinEvent);
                updateLastEventSeq(conn, member.roomId(), joinEvent.seq());

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException("Failed to add member", e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database connection failure", e);
        }
    }

    @Override
    public long countMembers(UUID roomId) {
        String sql = "SELECT count(*) FROM onmaru.map_room_members WHERE room_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count members", e);
        }
    }

    @Override
    public List<UserRoomMembership> findActiveRoomsByMemberId(UUID memberId, Instant now) {
        String sql = """
                SELECT r.id, r.status, r.expires_at, m.role, m.joined_at
                FROM onmaru.map_room_members m
                JOIN onmaru.map_rooms r ON r.id = m.room_id
                WHERE m.member_id = ? AND r.status = 'ACTIVE' AND r.expires_at > ?
                ORDER BY m.joined_at DESC
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, memberId);
            ps.setTimestamp(2, Timestamp.from(now));
            try (ResultSet rs = ps.executeQuery()) {
                List<UserRoomMembership> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new UserRoomMembership(
                            (UUID) rs.getObject("id"),
                            RoomStatus.valueOf(rs.getString("status")),
                            rs.getTimestamp("expires_at").toInstant(),
                            MemberRole.valueOf(rs.getString("role")),
                            rs.getTimestamp("joined_at").toInstant()
                    ));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list active rooms by member", e);
        }
    }

    @Override
    public List<Pin> findActivePins(UUID roomId) {
        String sql = """
                SELECT room_id, pin_id, place_public_id, place_name, lat, lng, added_by, added_at, added_seq
                FROM onmaru.map_room_pins
                WHERE room_id = ? AND removed_at IS NULL
                ORDER BY added_seq ASC
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Pin> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapPin(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find active pins", e);
        }
    }

    @Override
    public Optional<Pin> findActivePinByPlaceId(UUID roomId, String placeId) {
        String sql = """
                SELECT room_id, pin_id, place_public_id, place_name, lat, lng, added_by, added_at, added_seq
                FROM onmaru.map_room_pins
                WHERE room_id = ? AND place_public_id = ? AND removed_at IS NULL
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, roomId);
            ps.setString(2, placeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapPin(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find active pin by placeId", e);
        }
    }

    @Override
    public Optional<Pin> findActivePinById(UUID roomId, UUID pinId) {
        String sql = """
                SELECT room_id, pin_id, place_public_id, place_name, lat, lng, added_by, added_at, added_seq
                FROM onmaru.map_room_pins
                WHERE room_id = ? AND pin_id = ? AND removed_at IS NULL
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, roomId);
            ps.setObject(2, pinId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapPin(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find active pin by pinId", e);
        }
    }

    @Override
    public void addPin(Pin pin, RoomEvent event, long newLastEventSeq) {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 1. insert pin
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO onmaru.map_room_pins
                            (room_id, pin_id, place_public_id, place_name, lat, lng, added_by, added_at, added_seq)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    ps.setObject(1, pin.roomId());
                    ps.setObject(2, pin.pinId());
                    ps.setString(3, pin.placeId());
                    ps.setString(4, pin.placeName());
                    ps.setDouble(5, pin.lat());
                    ps.setDouble(6, pin.lng());
                    ps.setObject(7, pin.addedBy());
                    ps.setTimestamp(8, Timestamp.from(pin.addedAt()));
                    ps.setLong(9, pin.order());
                    ps.executeUpdate();
                }

                // 2. insert event
                insertEvent(conn, event);

                // 3. update last_event_seq
                updateLastEventSeq(conn, pin.roomId(), newLastEventSeq);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException("Failed to add pin in transaction", e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database connection failure", e);
        }
    }

    @Override
    public void removePin(UUID roomId, UUID pinId, UUID removedBy, Instant removedAt, long removedSeq, RoomEvent event, long newLastEventSeq) {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 1. update pin
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE onmaru.map_room_pins
                        SET removed_by = ?, removed_at = ?, removed_seq = ?
                        WHERE room_id = ? AND pin_id = ? AND removed_at IS NULL
                        """)) {
                    ps.setObject(1, removedBy);
                    ps.setTimestamp(2, Timestamp.from(removedAt));
                    ps.setLong(3, removedSeq);
                    ps.setObject(4, roomId);
                    ps.setObject(5, pinId);
                    int updated = ps.executeUpdate();
                    if (updated == 0) {
                        throw new IllegalStateException("Pin not found or already removed: " + pinId);
                    }
                }

                // 2. insert event
                insertEvent(conn, event);

                // 3. update last_event_seq
                updateLastEventSeq(conn, roomId, newLastEventSeq);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException("Failed to remove pin in transaction", e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database connection failure", e);
        }
    }

    @Override
    public Optional<RoomEvent> findEventByOpId(UUID roomId, UUID clientOpId) {
        String sql = """
                SELECT room_id, seq, event_type, actor_member_id, session_id, client_op_id, pin_id, payload, created_at
                FROM onmaru.map_room_events
                WHERE room_id = ? AND client_op_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, roomId);
            ps.setObject(2, clientOpId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapEvent(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find event by opId", e);
        }
    }

    @Override
    public List<RoomEvent> findEventsAfter(UUID roomId, long lastSeq) {
        String sql = """
                SELECT room_id, seq, event_type, actor_member_id, session_id, client_op_id, pin_id, payload, created_at
                FROM onmaru.map_room_events
                WHERE room_id = ? AND seq > ?
                ORDER BY seq ASC
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, roomId);
            ps.setLong(2, lastSeq);
            try (ResultSet rs = ps.executeQuery()) {
                List<RoomEvent> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapEvent(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find events after seq", e);
        }
    }

    @Override
    public List<Room> findExpiredActiveRooms(Instant now, int limit) {
        String sql = """
                SELECT id, created_by, status, created_at, expires_at, closed_at, closed_by, last_event_seq
                FROM onmaru.map_rooms
                WHERE status = 'ACTIVE' AND expires_at <= ?
                ORDER BY expires_at ASC
                LIMIT ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Room> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRoom(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find expired rooms", e);
        }
    }

    @Override
    public void closeRoom(UUID roomId, Instant closedAt, UUID closedBy, RoomEvent closeEvent) {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE onmaru.map_rooms
                        SET status = 'CLOSED'::onmaru.map_room_status,
                            closed_at = ?,
                            closed_by = ?,
                            last_event_seq = ?
                        WHERE id = ? AND status = 'ACTIVE'
                        """)) {
                    ps.setTimestamp(1, Timestamp.from(closedAt));
                    ps.setObject(2, closedBy);
                    ps.setLong(3, closeEvent.seq());
                    ps.setObject(4, roomId);
                    int updated = ps.executeUpdate();
                    if (updated == 0) {
                        conn.rollback();
                        return; // already closed
                    }
                }

                insertEvent(conn, closeEvent);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException("Failed to close room", e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database connection failure", e);
        }
    }

    private void insertEvent(Connection conn, RoomEvent event) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO onmaru.map_room_events
                    (room_id, seq, event_type, actor_member_id, session_id, client_op_id, pin_id, payload, created_at)
                VALUES (?, ?, ?::onmaru.map_room_event_type, ?, ?, ?, ?, ?::jsonb, ?)
                """)) {
            ps.setObject(1, event.roomId());
            ps.setLong(2, event.seq());
            ps.setString(3, event.eventType().name());
            ps.setObject(4, event.actorMemberId());
            ps.setObject(5, event.sessionId());
            ps.setObject(6, event.clientOpId());
            ps.setObject(7, event.pinId());
            ps.setString(8, event.payload() != null ? event.payload() : "{}");
            ps.setTimestamp(9, Timestamp.from(event.createdAt()));
            ps.executeUpdate();
        }
    }

    private void updateLastEventSeq(Connection conn, UUID roomId, long newLastSeq) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE onmaru.map_rooms
                SET last_event_seq = ?
                WHERE id = ?
                """)) {
            ps.setLong(1, newLastSeq);
            ps.setObject(2, roomId);
            ps.executeUpdate();
        }
    }

    private Room mapRoom(ResultSet rs) throws SQLException {
        Timestamp closedAtTs = rs.getTimestamp("closed_at");
        return new Room(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("created_by"),
                RoomStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                closedAtTs != null ? closedAtTs.toInstant() : null,
                (UUID) rs.getObject("closed_by"),
                rs.getLong("last_event_seq")
        );
    }

    private Invite mapInvite(ResultSet rs) throws SQLException {
        Timestamp revokedAtTs = rs.getTimestamp("revoked_at");
        return new Invite(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("room_id"),
                rs.getString("token_hash"),
                (UUID) rs.getObject("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                revokedAtTs != null ? revokedAtTs.toInstant() : null
        );
    }

    private Pin mapPin(ResultSet rs) throws SQLException {
        return new Pin(
                (UUID) rs.getObject("pin_id"),
                (UUID) rs.getObject("room_id"),
                rs.getString("place_public_id"),
                rs.getString("place_name"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                (UUID) rs.getObject("added_by"),
                rs.getTimestamp("added_at").toInstant(),
                rs.getLong("added_seq")
        );
    }

    private RoomEvent mapEvent(ResultSet rs) throws SQLException {
        return new RoomEvent(
                (UUID) rs.getObject("room_id"),
                rs.getLong("seq"),
                RoomEventType.valueOf(rs.getString("event_type")),
                (UUID) rs.getObject("actor_member_id"),
                (UUID) rs.getObject("session_id"),
                (UUID) rs.getObject("client_op_id"),
                (UUID) rs.getObject("pin_id"),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}

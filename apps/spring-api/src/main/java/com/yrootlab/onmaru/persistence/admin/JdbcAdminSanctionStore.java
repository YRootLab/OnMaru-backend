package com.yrootlab.onmaru.persistence.admin;

import com.yrootlab.onmaru.admin.users.AdminSanction;
import com.yrootlab.onmaru.admin.users.AdminSanctionStore;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class JdbcAdminSanctionStore implements AdminSanctionStore {

    private final DataSource dataSource;

    public JdbcAdminSanctionStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<AdminSanction> findByMember(UUID memberId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT id, member_id, status::text, reason, starts_at, ends_at, created_by,
                       revoked_by, revoked_at, created_at
                FROM onmaru.identity_member_sanctions
                WHERE member_id = ?
                ORDER BY created_at DESC, id DESC
                """)) {
            statement.setObject(1, memberId);
            try (var result = statement.executeQuery()) {
                List<AdminSanction> sanctions = new ArrayList<>();
                while (result.next()) {
                    sanctions.add(read(result));
                }
                return sanctions;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load member sanctions", exception);
        }
    }

    @Override
    public AdminSanction create(UUID memberId, UUID adminId, String reason, Instant startsAt, Instant endsAt) {
        UUID id = UUID.randomUUID();
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO onmaru.identity_member_sanctions
                    (id, member_id, status, reason, starts_at, ends_at, created_by)
                VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?)
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, memberId);
            statement.setString(3, reason);
            statement.setObject(4, startsAt);
            statement.setObject(5, endsAt);
            statement.setObject(6, adminId);
            statement.executeUpdate();
            return new AdminSanction(id, memberId, "ACTIVE", reason, startsAt, endsAt, adminId, null, null, startsAt);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to create member sanction", exception);
        }
    }

    @Override
    public boolean revoke(UUID sanctionId, UUID adminId, Instant revokedAt) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                UPDATE onmaru.identity_member_sanctions
                SET status = 'REVOKED', revoked_by = ?, revoked_at = ?
                WHERE id = ? AND status = 'ACTIVE' AND revoked_at IS NULL
                """)) {
            statement.setObject(1, adminId);
            statement.setObject(2, revokedAt);
            statement.setObject(3, sanctionId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to revoke member sanction", exception);
        }
    }

    @Override
    public int expireDue(Instant now) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                UPDATE onmaru.identity_member_sanctions
                SET status = 'EXPIRED'
                WHERE status = 'ACTIVE' AND ends_at IS NOT NULL AND ends_at <= ?
                """)) {
            statement.setObject(1, now);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to expire member sanctions", exception);
        }
    }

    private AdminSanction read(java.sql.ResultSet result) throws SQLException {
        return new AdminSanction(
                result.getObject("id", UUID.class), result.getObject("member_id", UUID.class),
                result.getString("status"), result.getString("reason"), instant(result, "starts_at"),
                optionalInstant(result, "ends_at"), result.getObject("created_by", UUID.class),
                result.getObject("revoked_by", UUID.class), optionalInstant(result, "revoked_at"),
                instant(result, "created_at"));
    }

    private Instant instant(java.sql.ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }

    private Instant optionalInstant(java.sql.ResultSet result, String column) throws SQLException {
        var value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

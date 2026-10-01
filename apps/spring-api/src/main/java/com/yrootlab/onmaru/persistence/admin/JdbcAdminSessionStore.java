package com.yrootlab.onmaru.persistence.admin;

import com.yrootlab.onmaru.admin.auth.AdminSession;
import com.yrootlab.onmaru.admin.auth.AdminSessionStore;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class JdbcAdminSessionStore implements AdminSessionStore {

    private final DataSource dataSource;

    public JdbcAdminSessionStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void save(AdminSession session) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO onmaru.identity_admin_sessions
                    (token_hash, admin_id, created_at, last_seen_at, expires_at, revoked_at, rotated_to_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            bind(statement, session);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to save admin session", exception);
        }
    }

    @Override
    public Optional<AdminSession> findByTokenHash(String tokenHash) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT token_hash, admin_id, created_at, last_seen_at, expires_at, revoked_at, rotated_to_hash
                FROM onmaru.identity_admin_sessions
                WHERE token_hash = ?
                """)) {
            statement.setString(1, tokenHash);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(read(result));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load admin session", exception);
        }
    }

    @Override
    public void replace(AdminSession current, AdminSession replacement) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var update = connection.prepareStatement("""
                    UPDATE onmaru.identity_admin_sessions
                    SET last_seen_at = ?, revoked_at = ?, rotated_to_hash = ?
                    WHERE token_hash = ? AND revoked_at IS NULL
                    """)) {
                update.setObject(1, current.lastSeenAt());
                update.setObject(2, current.revokedAt());
                update.setString(3, current.rotatedToHash());
                update.setString(4, current.tokenHash());
                if (update.executeUpdate() != 1) {
                    connection.rollback();
                    throw new IllegalStateException("Admin session was already rotated");
                }
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO onmaru.identity_admin_sessions
                        (token_hash, admin_id, created_at, last_seen_at, expires_at, revoked_at, rotated_to_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                bind(insert, replacement);
                insert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to rotate admin session", exception);
        }
    }

    @Override
    public void revoke(String tokenHash, Instant revokedAt) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                UPDATE onmaru.identity_admin_sessions
                SET revoked_at = COALESCE(revoked_at, ?), last_seen_at = ?
                WHERE token_hash = ?
                """)) {
            statement.setObject(1, revokedAt);
            statement.setObject(2, revokedAt);
            statement.setString(3, tokenHash);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to revoke admin session", exception);
        }
    }

    private static void bind(java.sql.PreparedStatement statement, AdminSession session) throws SQLException {
        statement.setString(1, session.tokenHash());
        statement.setObject(2, session.adminId());
        statement.setObject(3, session.createdAt());
        statement.setObject(4, session.lastSeenAt());
        statement.setObject(5, session.expiresAt());
        statement.setObject(6, session.revokedAt());
        statement.setString(7, session.rotatedToHash());
    }

    private static AdminSession read(java.sql.ResultSet result) throws SQLException {
        return new AdminSession(
                result.getString("token_hash"),
                result.getObject("admin_id", UUID.class),
                result.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                result.getObject("last_seen_at", java.time.OffsetDateTime.class).toInstant(),
                result.getObject("expires_at", java.time.OffsetDateTime.class).toInstant(),
                optionalInstant(result, "revoked_at"),
                result.getString("rotated_to_hash"));
    }

    private static Instant optionalInstant(java.sql.ResultSet result, String column) throws SQLException {
        var value = result.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

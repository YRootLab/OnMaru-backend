package com.yrootlab.onmaru.persistence.admin;

import com.yrootlab.onmaru.admin.auth.AdminJtiHasher;
import com.yrootlab.onmaru.admin.auth.AdminJtiRevocationStore;
import com.yrootlab.onmaru.admin.auth.AdminTokenStoreException;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

public final class JdbcAdminJtiRevocationStore implements AdminJtiRevocationStore {

    private final DataSource dataSource;
    private final AdminJtiHasher hasher;

    public JdbcAdminJtiRevocationStore(DataSource dataSource, AdminJtiHasher hasher) {
        this.dataSource = dataSource;
        this.hasher = hasher;
    }

    @Override
    public void revoke(UUID adminId, String jti, Instant expiresAt) {
        if (adminId == null || expiresAt == null) {
            throw new IllegalArgumentException("adminId and expiresAt are required");
        }
        String sql = """
                INSERT INTO onmaru.identity_admin_access_token_revocations
                    (jti_hash, admin_id, expires_at, revoked_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (jti_hash) DO UPDATE
                SET expires_at = greatest(
                    onmaru.identity_admin_access_token_revocations.expires_at,
                    EXCLUDED.expires_at)
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, hasher.hash(jti));
            statement.setObject(2, adminId);
            statement.setTimestamp(3, Timestamp.from(expiresAt));
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new AdminTokenStoreException("Failed to persist admin token revocation", exception);
        }
    }

    @Override
    public boolean isRevoked(String jti, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now is required");
        }
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM onmaru.identity_admin_access_token_revocations
                    WHERE jti_hash = ? AND expires_at > ?
                )
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, hasher.hash(jti));
            statement.setTimestamp(2, Timestamp.from(now));
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        } catch (Exception exception) {
            throw new AdminTokenStoreException("Failed to read admin token revocation", exception);
        }
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        if (now == null || limit < 1) {
            throw new IllegalArgumentException("now and positive limit are required");
        }
        String sql = """
                WITH expired AS (
                    SELECT jti_hash
                    FROM onmaru.identity_admin_access_token_revocations
                    WHERE expires_at <= ?
                    ORDER BY expires_at, jti_hash
                    LIMIT ?
                )
                DELETE FROM onmaru.identity_admin_access_token_revocations revocation
                USING expired
                WHERE revocation.jti_hash = expired.jti_hash
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setInt(2, limit);
            return statement.executeUpdate();
        } catch (Exception exception) {
            throw new AdminTokenStoreException("Failed to clean admin token revocations", exception);
        }
    }
}

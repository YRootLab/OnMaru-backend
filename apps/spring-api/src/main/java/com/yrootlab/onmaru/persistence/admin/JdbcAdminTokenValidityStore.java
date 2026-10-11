package com.yrootlab.onmaru.persistence.admin;

import com.yrootlab.onmaru.admin.auth.AdminAccountStatus;
import com.yrootlab.onmaru.admin.auth.AdminTokenValidity;
import com.yrootlab.onmaru.admin.auth.AdminTokenValidityStore;
import com.yrootlab.onmaru.admin.auth.AdminTokenStoreException;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class JdbcAdminTokenValidityStore implements AdminTokenValidityStore {

    private final DataSource dataSource;

    public JdbcAdminTokenValidityStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<AdminTokenValidity> findByAdminId(UUID adminId) {
        String sql = """
                SELECT status::text,
                       CASE WHEN tokens_valid_after = '-infinity'::timestamptz
                            THEN NULL ELSE tokens_valid_after END AS tokens_valid_after
                FROM onmaru.identity_admin_accounts
                WHERE id = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, adminId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                var timestamp = result.getTimestamp("tokens_valid_after");
                return Optional.of(new AdminTokenValidity(
                        AdminAccountStatus.valueOf(result.getString("status")),
                        timestamp == null ? Instant.MIN : timestamp.toInstant()));
            }
        } catch (SQLException exception) {
            throw new AdminTokenStoreException("Failed to load admin token validity", exception);
        }
    }
}

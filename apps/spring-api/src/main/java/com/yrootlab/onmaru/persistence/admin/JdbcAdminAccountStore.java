package com.yrootlab.onmaru.persistence.admin;

import com.yrootlab.onmaru.admin.auth.AdminAccount;
import com.yrootlab.onmaru.admin.auth.AdminAccountStatus;
import com.yrootlab.onmaru.admin.auth.AdminAccountStore;
import com.yrootlab.onmaru.admin.auth.AdminRole;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

public final class JdbcAdminAccountStore implements AdminAccountStore {

    private final DataSource dataSource;

    public JdbcAdminAccountStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<AdminAccount> findByEmail(String normalizedEmail) {
        String sql = """
                SELECT id, email, nickname, role::text, password_hash, status::text
                FROM onmaru.identity_admin_accounts
                WHERE email = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedEmail);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AdminAccount(
                        result.getObject("id", UUID.class),
                        result.getString("email"),
                        result.getString("nickname"),
                        AdminRole.valueOf(result.getString("role")),
                        result.getString("password_hash"),
                        AdminAccountStatus.valueOf(result.getString("status"))));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load admin account", exception);
        }
    }

    @Override
    public Optional<AdminAccount> findById(UUID id) {
        String sql = """
                SELECT id, email, nickname, role::text, password_hash, status::text
                FROM onmaru.identity_admin_accounts
                WHERE id = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AdminAccount(
                        result.getObject("id", UUID.class),
                        result.getString("email"),
                        result.getString("nickname"),
                        AdminRole.valueOf(result.getString("role")),
                        result.getString("password_hash"),
                        AdminAccountStatus.valueOf(result.getString("status"))));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load admin account", exception);
        }
    }

    @Override
    public void recordLogin(AdminAccount account) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                UPDATE onmaru.identity_admin_accounts
                SET last_login_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """)) {
            statement.setObject(1, account.id());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to update admin last login", exception);
        }
    }
}

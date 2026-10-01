package com.yrootlab.onmaru.admin.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;

@Component
@Profile("production")
public final class AdminBootstrapProvisioner implements ApplicationRunner {
    private final DataSource dataSource;
    private final AdminPasswordHasher passwordHasher;
    private final AdminBootstrapProperties properties;

    public AdminBootstrapProvisioner(
            DataSource dataSource,
            AdminPasswordHasher passwordHasher,
            AdminBootstrapProperties properties) {
        this.dataSource = dataSource;
        this.passwordHasher = passwordHasher;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!properties.configured()) {
            return;
        }
        String email = properties.getEmail().trim().toLowerCase(java.util.Locale.ROOT);
        String nickname = properties.getNickname() == null || properties.getNickname().isBlank()
                ? "운영 관리자" : properties.getNickname().trim();
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO onmaru.identity_admin_accounts
                        (id, email, password_hash, nickname, role, status)
                    VALUES (?, ?, ?, ?, 'ADMIN', 'ACTIVE')
                    ON CONFLICT (lower(email)) DO NOTHING
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, email);
                statement.setString(3, passwordHasher.encode(properties.getPassword()));
                statement.setString(4, nickname);
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to bootstrap admin account", exception);
        }
    }
}

package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.admin.auth.AdminSession;
import com.yrootlab.onmaru.persistence.admin.JdbcAdminSessionStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAdminSessionStoreTests {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private DataSource dataSource;

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetDatabase() throws Exception {
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
        dataSource = new DriverManagerDataSource(jdbcUrl(), "onmaru_test", "onmaru_test");
    }

    @Test
    void savesAndReloadsSessionTimestampsInPostgres() throws Exception {
        var adminId = UUID.randomUUID();
        seedAdmin(adminId);
        var createdAt = Instant.parse("2026-10-03T09:31:41Z");
        var session = session("a", adminId, createdAt);

        var store = new JdbcAdminSessionStore(dataSource);
        store.save(session);

        assertThat(store.findByTokenHash(session.tokenHash())).contains(session);
    }

    @Test
    void rotatesSessionTimestampsInPostgres() throws Exception {
        var adminId = UUID.randomUUID();
        seedAdmin(adminId);
        var createdAt = Instant.parse("2026-10-03T09:31:41Z");
        var rotatedAt = createdAt.plusSeconds(60);
        var current = session("a", adminId, createdAt);
        var replacement = session("b", adminId, rotatedAt);
        var rotatedCurrent = current.rotatedTo(replacement.tokenHash(), rotatedAt);
        var store = new JdbcAdminSessionStore(dataSource);
        store.save(current);

        store.replace(rotatedCurrent, replacement);

        assertThat(store.findByTokenHash(current.tokenHash())).contains(rotatedCurrent);
        assertThat(store.findByTokenHash(replacement.tokenHash())).contains(replacement);
    }

    @Test
    void revokesSessionTimestampInPostgres() throws Exception {
        var adminId = UUID.randomUUID();
        seedAdmin(adminId);
        var createdAt = Instant.parse("2026-10-03T09:31:41Z");
        var revokedAt = createdAt.plusSeconds(60);
        var session = session("a", adminId, createdAt);
        var store = new JdbcAdminSessionStore(dataSource);
        store.save(session);

        store.revoke(session.tokenHash(), revokedAt);

        assertThat(store.findByTokenHash(session.tokenHash()))
                .contains(new AdminSession(
                        session.tokenHash(), adminId, createdAt, revokedAt,
                        session.expiresAt(), revokedAt, null));
    }

    private static AdminSession session(String hashCharacter, UUID adminId, Instant createdAt) {
        return new AdminSession(
                hashCharacter.repeat(64),
                adminId,
                createdAt,
                createdAt,
                createdAt.plusSeconds(14 * 24 * 60 * 60),
                null,
                null);
    }

    private void seedAdmin(UUID adminId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.identity_admin_accounts
                         (id, email, password_hash, nickname, role, status)
                     VALUES (?, 'admin-session@example.com', 'hash', 'Admin', 'ADMIN', 'ACTIVE')
                     """)) {
            statement.setObject(1, adminId);
            statement.executeUpdate();
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/onmaru_test".formatted(
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432));
    }
}

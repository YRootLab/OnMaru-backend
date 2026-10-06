package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.admin.auth.AdminJtiHasher;
import com.yrootlab.onmaru.persistence.admin.JdbcAdminJtiRevocationStore;
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
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAdminJtiRevocationStoreTests {

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
        Flyway.configure().dataSource(jdbcUrl(), "onmaru_test", "onmaru_test")
                .locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        dataSource = new DriverManagerDataSource(jdbcUrl(), "onmaru_test", "onmaru_test");
    }

    @Test
    void sharesHashedRevocationAcrossStoreInstancesUntilExpiry() throws Exception {
        UUID adminId = seedAdmin();
        Instant now = Instant.parse("2026-10-06T01:00:00Z");
        var first = store();
        var second = store();

        first.revoke(adminId, "secret-jti", now.plusSeconds(60));

        assertThat(second.isRevoked("secret-jti", now)).isTrue();
        assertThat(second.isRevoked("secret-jti", now.plusSeconds(60))).isFalse();
        assertThat(storedHash()).isEqualTo(new AdminJtiHasher().hash("secret-jti"));
    }

    @Test
    void concurrentRevokeIsIdempotentAndKeepsLatestExpiry() throws Exception {
        UUID adminId = seedAdmin();
        Instant now = Instant.parse("2026-10-06T01:00:00Z");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> store().revoke(adminId, "same-jti", now.plusSeconds(30)));
            var second = executor.submit(() -> store().revoke(adminId, "same-jti", now.plusSeconds(90)));
            first.get();
            second.get();
        }

        assertThat(countRows()).isEqualTo(1);
        assertThat(store().isRevoked("same-jti", now.plusSeconds(60))).isTrue();
    }

    @Test
    void cleanupDeletesOnlyExpiredRowsWithinBatchLimit() throws Exception {
        UUID adminId = seedAdmin();
        Instant now = Instant.parse("2026-10-06T01:00:00Z");
        var store = store();
        store.revoke(adminId, "expired-1", now.minusSeconds(2));
        store.revoke(adminId, "expired-2", now.minusSeconds(1));
        store.revoke(adminId, "live", now.plusSeconds(60));

        assertThat(store.deleteExpired(now, 1)).isEqualTo(1);
        assertThat(countRows()).isEqualTo(2);
        assertThat(store.isRevoked("live", now)).isTrue();
    }

    private JdbcAdminJtiRevocationStore store() {
        return new JdbcAdminJtiRevocationStore(dataSource, new AdminJtiHasher());
    }

    private UUID seedAdmin() throws Exception {
        UUID id = UUID.randomUUID();
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO onmaru.identity_admin_accounts
                    (id, email, password_hash, nickname, role, status)
                VALUES (?, ?, 'hash', 'Admin', 'ADMIN', 'ACTIVE')
                """)) {
            statement.setObject(1, id);
            statement.setString(2, id + "@example.com");
            statement.executeUpdate();
        }
        return id;
    }

    private String storedHash() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT jti_hash FROM onmaru.identity_admin_access_token_revocations")) {
            result.next();
            return result.getString(1);
        }
    }

    private int countRows() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM onmaru.identity_admin_access_token_revocations")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/onmaru_test".formatted(
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432));
    }
}

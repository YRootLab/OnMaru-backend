package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.admin.auth.AdminAccountStatus;
import com.yrootlab.onmaru.persistence.admin.JdbcAdminAccountStore;
import com.yrootlab.onmaru.persistence.admin.JdbcAdminTokenValidityStore;
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

class JdbcAdminTokenValidityStoreTests {

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
    void disablingAdminAtomicallyAdvancesTokenValidityBoundaryToNextEpochSecond() throws Exception {
        UUID adminId = UUID.randomUUID();
        seedAdmin(adminId);
        var changedAt = Instant.parse("2026-10-06T00:00:00.500Z");

        new JdbcAdminAccountStore(dataSource).changeStatus(adminId, AdminAccountStatus.DISABLED, changedAt);

        var validity = new JdbcAdminTokenValidityStore(dataSource).findByAdminId(adminId).orElseThrow();
        assertThat(validity.status()).isEqualTo(AdminAccountStatus.DISABLED);
        assertThat(validity.tokensValidAfter()).isEqualTo(Instant.parse("2026-10-06T00:00:01Z"));
    }

    @Test
    void activeAdminDefaultsToUnboundedPastTokenValidity() throws Exception {
        UUID adminId = UUID.randomUUID();
        seedAdmin(adminId);

        var validity = new JdbcAdminTokenValidityStore(dataSource).findByAdminId(adminId).orElseThrow();

        assertThat(validity.status()).isEqualTo(AdminAccountStatus.ACTIVE);
        assertThat(validity.tokensValidAfter()).isEqualTo(Instant.MIN);
    }

    private void seedAdmin(UUID adminId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.identity_admin_accounts
                         (id, email, password_hash, nickname, role, status)
                     VALUES (?, 'validity@example.com', 'hash', 'Admin', 'ADMIN', 'ACTIVE')
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

package com.yrootlab.onmaru.testing.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayMigrationBaselineTests {

    private static final int POSTGRES_PORT = 5432;
    private static final String DATABASE = "onmaru_test";
    private static final String USERNAME = "onmaru_test";
    private static final String PASSWORD = "onmaru_test";

    private static final GenericContainer<?> postgres = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(POSTGRES_PORT)
            .withEnv("POSTGRES_DB", DATABASE)
            .withEnv("POSTGRES_USER", USERNAME)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @BeforeAll
    static void startPostgres() {
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @Test
    void migratesEmptyDatabaseToD01Baseline() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD)) {
            PostgresTestDatabase.reset(connection);
        }

        migrate();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, "SELECT COUNT(*) FROM flyway_schema_history WHERE success")).isPositive();
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.schemata
                    WHERE schema_name IN ('onmaru', 'onmaru_registry')
                    """)).isEqualTo(2);
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM onmaru_registry.migration_version_reservations
                    WHERE reserved_for = 'D01'
                      AND version = '001'
                    """)).isEqualTo(1);
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM pg_roles
                    WHERE rolname IN ('onmaru_migration', 'onmaru_runtime', 'onmaru_readonly', 'onmaru_backup')
                      AND rolcanlogin = false
                    """)).isEqualTo(4);
        }
    }

    @Test
    void upgradesDatabaseWithExistingBaselineMarker() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            PostgresTestDatabase.reset(connection);
            statement.execute("CREATE TABLE legacy_baseline_marker (id integer PRIMARY KEY)");
            statement.execute("INSERT INTO legacy_baseline_marker (id) VALUES (1)");
        }

        migrate();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, "SELECT COUNT(*) FROM legacy_baseline_marker")).isEqualTo(1);
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru_registry.migration_version_reservations"))
                    .isPositive();
        }
    }

    @Test
    void runtimeRoleCannotCreateTables() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD)) {
            PostgresTestDatabase.reset(connection);
        }

        migrate();
        createLoginRole("onmaru_runtime_login", "onmaru_runtime_login", "onmaru_runtime");

        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_runtime_login", "onmaru_runtime_login");
             var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("CREATE TABLE onmaru.runtime_ddl_probe (id integer)"))
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void futureMigrationTablesGrantRuntimeAndReadonlyAccess() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD)) {
            PostgresTestDatabase.reset(connection);
        }

        migrate();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE onmaru.default_privilege_probe (id integer PRIMARY KEY)");
            statement.execute("INSERT INTO onmaru.default_privilege_probe (id) VALUES (1)");
        }

        createLoginRole("onmaru_runtime_login", "onmaru_runtime_login", "onmaru_runtime");
        createLoginRole("onmaru_readonly_login", "onmaru_readonly_login", "onmaru_readonly");

        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_runtime_login", "onmaru_runtime_login");
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.default_privilege_probe")).isEqualTo(1);
            statement.execute("INSERT INTO onmaru.default_privilege_probe (id) VALUES (2)");
        }

        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_readonly_login", "onmaru_readonly_login");
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.default_privilege_probe")).isEqualTo(2);
            assertThatThrownBy(() -> statement.execute("INSERT INTO onmaru.default_privilege_probe (id) VALUES (3)"))
                    .hasMessageContaining("permission denied");
        }
    }

    private static void migrate() {
        Flyway.configure()
                .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                .locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private static int countRows(java.sql.Statement statement, String sql) throws Exception {
        try (var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static void createLoginRole(String username, String password, String memberOf) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP ROLE IF EXISTS " + username);
            statement.execute("CREATE ROLE " + username + " LOGIN PASSWORD '" + password + "' IN ROLE " + memberOf);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(),
                postgres.getMappedPort(POSTGRES_PORT),
                DATABASE);
    }
}

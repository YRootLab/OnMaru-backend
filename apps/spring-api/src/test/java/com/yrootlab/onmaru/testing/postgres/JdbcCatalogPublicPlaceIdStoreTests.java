package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.catalog.publicid.CatalogPublicPlaceIdConflictException;
import com.yrootlab.onmaru.persistence.catalog.JdbcCatalogPublicPlaceIdStore;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcCatalogPublicPlaceIdStoreTests {

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
    void persistsAStableOneToOnePublicPlaceIdMappingAcrossStoreInstances() throws Exception {
        var firstPlaceId = UUID.randomUUID();
        var secondPlaceId = UUID.randomUUID();
        seedPlaceIdentity(firstPlaceId);
        seedPlaceIdentity(secondPlaceId);

        var store = new JdbcCatalogPublicPlaceIdStore(dataSource);
        store.register("p-jeonju-hanok-village", firstPlaceId);
        store.register("p-jeonju-hanok-village", firstPlaceId);

        var reloadedStore = new JdbcCatalogPublicPlaceIdStore(dataSource);
        assertThat(reloadedStore.findPlaceId("p-jeonju-hanok-village")).contains(firstPlaceId);
        assertThatThrownBy(() -> reloadedStore.register("p-jeonju-hanok-village", secondPlaceId))
                .isInstanceOf(CatalogPublicPlaceIdConflictException.class);
        assertThatThrownBy(() -> reloadedStore.register("p-another-name", firstPlaceId))
                .isInstanceOf(CatalogPublicPlaceIdConflictException.class);
    }

    private void seedPlaceIdentity(UUID placeId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.catalog_place_identity (id, created_at)
                     VALUES (?, ?)
                     """)) {
            statement.setObject(1, placeId);
            statement.setObject(2, OffsetDateTime.of(2026, 9, 24, 0, 0, 0, 0, ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
    }
}

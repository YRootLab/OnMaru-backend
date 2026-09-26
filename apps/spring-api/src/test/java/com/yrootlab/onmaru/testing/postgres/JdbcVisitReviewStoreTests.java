package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.persistence.catalog.JdbcCatalogPublicPlaceIdStore;
import com.yrootlab.onmaru.persistence.community.JdbcVisitReviewStore;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcVisitReviewStoreTests {

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
    void preservesThePublicPlaceSnapshotAndLikesAcrossStoreInstances() throws Exception {
        var catalogPlaceId = UUID.randomUUID();
        var authorId = UUID.randomUUID();
        var likerId = UUID.randomUUID();
        seedPlaceIdentity(catalogPlaceId);
        seedMember(authorId);
        seedMember(likerId);

        var publicPlaceIds = new JdbcCatalogPublicPlaceIdStore(dataSource);
        publicPlaceIds.register("p-jeonju-hanok-village", catalogPlaceId);
        var review = new VisitReviewProjection(
                UUID.randomUUID(),
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                "kr-45-jeonju",
                35.8151,
                127.1530,
                "처마 아래에서 쉬기 좋았습니다.",
                "한적",
                5,
                List.of("고즈넉함", "처마"),
                Instant.parse("2026-09-24T00:00:00Z"),
                authorId,
                Set.of(likerId),
                VisitReviewStatus.PUBLISHED);

        new JdbcVisitReviewStore(dataSource, publicPlaceIds).add(review);

        assertThat(new JdbcVisitReviewStore(dataSource, publicPlaceIds).findSnapshot())
                .containsExactly(review);
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

    private void seedMember(UUID memberId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.identity_members (id, status, created_at)
                     VALUES (?, 'ACTIVE', ?)
                     """)) {
            statement.setObject(1, memberId);
            statement.setObject(2, OffsetDateTime.of(2026, 9, 24, 0, 0, 0, 0, ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
    }
}

package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokMediaType;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokPlacement;
import com.yrootlab.onmaru.persistence.screenhanok.JdbcScreenHanokPlacementStore;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcScreenHanokPlacementStoreTests {

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
    void publishAtomicallyReplacesThePreviousSnapshotAndKeepsSourceBackedFields() {
        var store = new JdbcScreenHanokPlacementStore(dataSource);
        var publishedAt = Instant.parse("2026-09-23T00:00:00Z");
        store.publish(List.of(
                placement("p-andong-manhyujeong", ScreenHanokMediaType.K_DRAMA, "미스터 션샤인", publishedAt),
                placement("p-jeonju-hanok-village", ScreenHanokMediaType.CINEMA, "영화 한옥", publishedAt)
        ));

        var reloadedStore = new JdbcScreenHanokPlacementStore(dataSource);
        assertThat(reloadedStore.current()).containsExactlyInAnyOrder(
                placement("p-andong-manhyujeong", ScreenHanokMediaType.K_DRAMA, "미스터 션샤인", publishedAt),
                placement("p-jeonju-hanok-village", ScreenHanokMediaType.CINEMA, "영화 한옥", publishedAt)
        );

        var replacement = placement("p-gyeongju-yangdong", ScreenHanokMediaType.KPOP, "한옥 뮤직비디오", publishedAt.plusSeconds(60));
        reloadedStore.publish(List.of(replacement));

        assertThat(new JdbcScreenHanokPlacementStore(dataSource).current()).containsExactly(replacement);
    }

    private ScreenHanokPlacement placement(
            String placeId,
            ScreenHanokMediaType mediaType,
            String workTitle,
            Instant publishedAt) {
        return new ScreenHanokPlacement(
                placeId,
                mediaType,
                workTitle,
                "출처로 확인한 촬영지입니다.",
                List.of("#한옥", "#촬영지"),
                "https://example.com/" + placeId,
                "공식 출처",
                publishedAt);
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
    }
}

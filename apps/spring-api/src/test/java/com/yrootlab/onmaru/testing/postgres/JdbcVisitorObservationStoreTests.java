package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.insights.observation.ObservationCoverageStatus;
import com.yrootlab.onmaru.insights.observation.ObservationMetric;
import com.yrootlab.onmaru.insights.observation.SpatialLevel;
import com.yrootlab.onmaru.insights.observation.VisitorObservation;
import com.yrootlab.onmaru.persistence.insights.JdbcVisitorObservationStore;
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
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcVisitorObservationStoreTests {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private DataSource dataSource;
    private UUID activeRevisionId;

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
        activeRevisionId = UUID.randomUUID();
        seedRegion("kr-45-jeonju");
        seedActiveDataLabRevision(activeRevisionId);
    }

    @Test
    void returnsOnlyTheLatestCompleteCountFromTheActiveDataLabRevisionAcrossStoreInstances() {
        var store = new JdbcVisitorObservationStore(dataSource);
        store.save(activeRevisionId, complete("2026-09-14", 18240L));
        store.save(activeRevisionId, complete("2026-09-15", 19420L));

        assertThat(new JdbcVisitorObservationStore(dataSource)
                .findLatestCompleteByRegionCodes(Set.of("kr-45-jeonju", "kr-11-seoul")))
                .isEqualTo(Map.of("kr-45-jeonju", 19420L));
    }

    @Test
    void omitsNotAvailableCountsInsteadOfSynthesizingZero() {
        var store = new JdbcVisitorObservationStore(dataSource);
        store.save(activeRevisionId, new VisitorObservation(
                "KTO_DATALAB", "kr-45-jeonju", LocalDate.parse("2026-09-15"),
                ObservationMetric.VISITOR_COUNT, null, "persons", SpatialLevel.SIGUNGU,
                ObservationCoverageStatus.NOT_AVAILABLE, Instant.parse("2026-09-16T00:00:00Z")));

        assertThat(store.findLatestCompleteByRegionCodes(Set.of("kr-45-jeonju"))).isEmpty();
    }

    private VisitorObservation complete(String basisDate, long value) {
        return new VisitorObservation(
                "KTO_DATALAB", "kr-45-jeonju", LocalDate.parse(basisDate),
                ObservationMetric.VISITOR_COUNT, value, "persons", SpatialLevel.SIGUNGU,
                ObservationCoverageStatus.COMPLETE, Instant.parse("2026-09-16T00:00:00Z"));
    }

    private void seedRegion(String code) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.catalog_regions (id, code, name, level, active)
                     VALUES (?, ?, '전북 전주시', 'SIGUNGU', true)
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, code);
            statement.executeUpdate();
        }
    }

    private void seedActiveDataLabRevision(UUID revisionId) throws Exception {
        try (var connection = dataSource.getConnection();
             var revision = connection.prepareStatement("""
                     INSERT INTO onmaru.catalog_dataset_revisions
                         (id, dataset, status, fetched_at, published_at)
                     VALUES (?, 'kto-datalab-visitor', 'PUBLISHED', now(), now())
                     """ );
             var active = connection.prepareStatement("""
                     INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                     VALUES ('kto-datalab-visitor', ?, now())
                     """)) {
            revision.setObject(1, revisionId);
            revision.executeUpdate();
            active.setObject(1, revisionId);
            active.executeUpdate();
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
    }
}

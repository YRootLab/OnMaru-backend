package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.catalog.region.DataLabRegionMapping;
import com.yrootlab.onmaru.catalog.region.DataLabRegionMappingStatus;
import com.yrootlab.onmaru.insights.observation.ObservationCoverageStatus;
import com.yrootlab.onmaru.insights.observation.ObservationMetric;
import com.yrootlab.onmaru.insights.observation.SpatialLevel;
import com.yrootlab.onmaru.insights.observation.VisitorObservation;
import com.yrootlab.onmaru.persistence.catalog.JdbcDataLabRegionMappingRegistry;
import com.yrootlab.onmaru.persistence.insights.JdbcDataLabVisitorSnapshotPublisher;
import com.yrootlab.onmaru.persistence.insights.JdbcDataLabCollectionGuard;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcDataLabVisitorSnapshotPublisherTests {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private DataSource dataSource;
    private UUID oldRevisionId;

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
        seedRegionsAndCurrentSourceCodes();
        oldRevisionId = seedActiveRevision();
        new JdbcVisitorObservationStore(dataSource).save(oldRevisionId, complete("2026-09-20", 100L));
    }

    @Test
    void resolvesOnlyActiveCurrentRegionSourceCodesForTheProviderAndDataset() {
        var sourceCodes = new JdbcDataLabRegionMappingRegistry(dataSource)
                .findCurrent(LocalDate.parse("2026-09-26"));

        assertThat(sourceCodes).containsExactly(new DataLabRegionMapping(
                "kr-45-jeonju", "SIGUNGU:52110", DataLabRegionMapping.Level.SIGUNGU, "전주시",
                "https://www.data.go.kr/data/15101972/openapi.do",
                Instant.parse("2026-09-26T00:00:00Z"),
                "onmaru-catalog-data-verification",
                Instant.parse("2026-09-26T00:00:00Z"),
                DataLabRegionMappingStatus.ACTIVE));
    }

    @Test
    void publishesACompleteSnapshotByReplacingTheActiveRevisionOnlyAfterStagingObservations() throws Exception {
        var publisher = new JdbcDataLabVisitorSnapshotPublisher(dataSource);

        UUID publishedRevisionId = publisher.publish(
                Instant.parse("2026-09-25T03:30:00Z"),
                List.of(complete("2026-09-25", 200L)));

        assertThat(activeRevisionId()).isEqualTo(publishedRevisionId);
        assertThat(revision(publishedRevisionId)).containsEntry("status", "PUBLISHED")
                .containsEntry("base_revision_id", oldRevisionId.toString());
        assertThat(new JdbcVisitorObservationStore(dataSource)
                .findLatestCompleteByRegionCodes(Set.of("kr-45-jeonju")))
                .isEqualTo(Map.of("kr-45-jeonju", 200L));
    }

    @Test
    void keepsThePreviousActiveRevisionWhenStagingFails() throws Exception {
        var publisher = new JdbcDataLabVisitorSnapshotPublisher(dataSource);
        var invalid = new VisitorObservation(
                "KTO_DATALAB", "kr-missing-region", LocalDate.parse("2026-09-25"),
                ObservationMetric.VISITOR_COUNT, 50L, "persons", SpatialLevel.SIGUNGU,
                ObservationCoverageStatus.COMPLETE, Instant.parse("2026-09-25T03:30:00Z"));

        assertThatThrownBy(() -> publisher.publish(Instant.parse("2026-09-25T03:30:00Z"), List.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No active Catalog region");

        assertThat(activeRevisionId()).isEqualTo(oldRevisionId);
        assertThat(revisionCount()).isEqualTo(1);
    }

    @Test
    void serializesCollectionAcrossIndependentApplicationInstances() {
        var firstInstance = new JdbcDataLabCollectionGuard(dataSource);
        var secondInstance = new JdbcDataLabCollectionGuard(dataSource);

        try (var firstLease = firstInstance.tryAcquire().orElseThrow()) {
            assertThat(secondInstance.tryAcquire()).isEmpty();
        }

        try (var secondLease = secondInstance.tryAcquire().orElseThrow()) {
            assertThat(secondLease).isNotNull();
        }
    }

    private VisitorObservation complete(String basisDate, long count) {
        return new VisitorObservation(
                "KTO_DATALAB", "kr-45-jeonju", LocalDate.parse(basisDate),
                ObservationMetric.VISITOR_COUNT, count, "persons", SpatialLevel.SIGUNGU,
                ObservationCoverageStatus.COMPLETE, Instant.parse("2026-09-25T03:30:00Z"));
    }

    private void seedRegionsAndCurrentSourceCodes() throws Exception {
        UUID activeRegionId = UUID.randomUUID();
        UUID parentRegionId = UUID.randomUUID();
        UUID inactiveRegionId = UUID.randomUUID();
        UUID unverifiedActiveRegionId = UUID.randomUUID();
        try (var connection = dataSource.getConnection();
             var region = connection.prepareStatement("""
                     INSERT INTO onmaru.catalog_regions (id, parent_id, code, name, level, active)
                     VALUES (?, ?, ?, ?, ?::onmaru.catalog_region_level, ?)
                     """);
             var sourceCode = connection.prepareStatement("""
                     INSERT INTO onmaru.catalog_region_source_codes
                         (provider, dataset, source_code, valid_from, valid_to, region_id)
                     VALUES ('KTO_DATALAB', 'visitor', ?, ?, ?, ?)
                     """)) {
            insertRegion(region, parentRegionId, null, "kr-test-parent", "테스트 광역", "SIDO", true);
            insertRegion(region, activeRegionId, parentRegionId, "kr-45-jeonju", "전북 전주시", "SIGUNGU", true);
            insertRegion(region, inactiveRegionId, parentRegionId, "kr-11-jongno", "서울 종로구", "SIGUNGU", false);
            insertRegion(region, unverifiedActiveRegionId, parentRegionId, "kr-48-jinju", "경남 진주시", "SIGUNGU", true);
            insertSourceCode(sourceCode, "expired", LocalDate.parse("2020-01-01"), LocalDate.parse("2026-09-24"), activeRegionId);
            insertSourceCode(sourceCode, "11110", LocalDate.parse("2020-01-01"), null, inactiveRegionId);
            insertSourceCode(sourceCode, "48170", LocalDate.parse("2020-01-01"), null, unverifiedActiveRegionId);
        }
    }

    private void insertRegion(
            java.sql.PreparedStatement statement,
            UUID id,
            UUID parentId,
            String code,
            String name,
            String level,
            boolean active)
            throws Exception {
        statement.setObject(1, id);
        if (parentId == null) {
            statement.setNull(2, java.sql.Types.OTHER);
        } else {
            statement.setObject(2, parentId);
        }
        statement.setString(3, code);
        statement.setString(4, name);
        statement.setString(5, level);
        statement.setBoolean(6, active);
        statement.executeUpdate();
    }

    private void insertSourceCode(
            java.sql.PreparedStatement statement,
            String sourceCode,
            LocalDate validFrom,
            LocalDate validTo,
            UUID regionId) throws Exception {
        statement.setString(1, sourceCode);
        statement.setObject(2, validFrom);
        if (validTo == null) {
            statement.setNull(3, java.sql.Types.DATE);
        } else {
            statement.setObject(3, validTo);
        }
        statement.setObject(4, regionId);
        statement.executeUpdate();
    }

    private void insertOfficialVerification(java.sql.Connection connection, String sourceCode, LocalDate validFrom)
            throws Exception {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_region_source_code_verifications
                    (provider, dataset, source_code, valid_from, official_source_url, verified_at, verified_by)
                VALUES ('KTO_DATALAB', 'visitor', ?, ?, 'https://api.visitkorea.or.kr/official-codebook', now(), 'catalog-operator')
                """)) {
            statement.setString(1, sourceCode);
            statement.setObject(2, validFrom);
            statement.executeUpdate();
        }
    }

    private UUID seedActiveRevision() throws Exception {
        UUID revisionId = UUID.randomUUID();
        try (var connection = dataSource.getConnection();
             var revision = connection.prepareStatement("""
                     INSERT INTO onmaru.catalog_dataset_revisions
                         (id, dataset, status, fetched_at, published_at)
                     VALUES (?, 'kto-datalab-visitor', 'PUBLISHED', now(), now())
                     """);
             var active = connection.prepareStatement("""
                     INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                     VALUES ('kto-datalab-visitor', ?, now())
                     """)) {
            revision.setObject(1, revisionId);
            revision.executeUpdate();
            active.setObject(1, revisionId);
            active.executeUpdate();
        }
        return revisionId;
    }

    private UUID activeRevisionId() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT revision_id FROM onmaru.catalog_active_datasets
                     WHERE dataset = 'kto-datalab-visitor'
                     """)) {
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private Map<String, String> revision(UUID revisionId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT status::text, base_revision_id::text
                     FROM onmaru.catalog_dataset_revisions WHERE id = ?
                     """)) {
            statement.setObject(1, revisionId);
            try (var result = statement.executeQuery()) {
                result.next();
                return Map.of("status", result.getString(1), "base_revision_id", result.getString(2));
            }
        }
    }

    private long revisionCount() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM onmaru.catalog_dataset_revisions");
             var result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
    }
}

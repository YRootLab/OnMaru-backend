package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.catalog.externalplace.ExternalPlaceCandidate;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlaceIdentityConflictException;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlacePolicy;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlaceProvider;
import com.yrootlab.onmaru.persistence.catalog.JdbcExternalPlaceRegistry;
import com.yrootlab.onmaru.persistence.jdbc.JdbcTransactionRunner;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcExternalPlaceRegistryTests {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private DriverManagerDataSource dataSource;
    private JdbcTransactionRunner transactions;
    private AtomicLong ids;

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
        transactions = new JdbcTransactionRunner(dataSource);
        ids = new AtomicLong();
    }

    @Test
    void createsCanonicalIdentityPublicIdAndExternalSnapshot() {
        var created = registry().resolveOrCreate(candidate("123456789", "대청댐", 36.4952, 127.4981),
                "kr-30-daedeok");

        assertThat(created.created()).isTrue();
        assertThat(created.publicPlaceId()).matches("p-ext-[a-f0-9]{32}");
        assertThat(created.publicPlaceId()).doesNotContain("123456789");
        assertThat(count("catalog_place_identity")).isOne();
        assertThat(count("catalog_place_public_ids")).isOne();
        assertThat(count("catalog_external_places")).isOne();
    }

    @Test
    void reusesStoredSnapshotWithoutRenamingOrMovingItWithinTolerance() {
        var first = registry().resolveOrCreate(candidate("123", "기존 이름", 37.0, 127.0), "kr-11-jongno");

        var replay = registry().resolveOrCreate(candidate("123", "변경된 이름", 37.004, 127.0), "kr-11-gangnam");

        assertThat(replay.created()).isFalse();
        assertThat(replay.placeId()).isEqualTo(first.placeId());
        assertThat(replay.publicPlaceId()).isEqualTo(first.publicPlaceId());
        assertThat(replay.name()).isEqualTo("기존 이름");
        assertThat(replay.regionCode()).isEqualTo("kr-11-jongno");
        assertThat(replay.lat()).isEqualTo(37.0);
        assertThat(replay.lng()).isEqualTo(127.0);
    }

    @Test
    void rejectsAnExistingExternalIdMoreThanOneKilometerAway() {
        registry().resolveOrCreate(candidate("123", "기존 장소", 37.0, 127.0), "kr-11-jongno");

        assertThatThrownBy(() -> registry().resolveOrCreate(
                candidate("123", "충돌 장소", 37.02, 127.0), "kr-11-jongno"))
                .isInstanceOf(ExternalPlaceIdentityConflictException.class);
    }

    @Test
    void concurrentFirstRegistrationsConvergeToOnePlace() throws Exception {
        var store = registry();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> store.resolveOrCreate(
                    candidate("race", "동시 장소", 37.0, 127.0), "kr-11-jongno"));
            var second = executor.submit(() -> store.resolveOrCreate(
                    candidate("race", "동시 장소", 37.0, 127.0), "kr-11-jongno"));

            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(firstResult.placeId()).isEqualTo(secondResult.placeId());
            assertThat(count("catalog_place_identity")).isOne();
            assertThat(count("catalog_place_public_ids")).isOne();
            assertThat(count("catalog_external_places")).isOne();
        }
    }

    @Test
    void outerTransactionRollbackRemovesAllRegistryRows() {
        assertThatThrownBy(() -> transactions.execute(ignored -> {
            registry().resolveOrCreate(candidate("rollback", "롤백 장소", 37.0, 127.0), "kr-unassigned");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("catalog_place_identity")).isZero();
        assertThat(count("catalog_place_public_ids")).isZero();
        assertThat(count("catalog_external_places")).isZero();
    }

    private JdbcExternalPlaceRegistry registry() {
        return new JdbcExternalPlaceRegistry(transactions, new ExternalPlacePolicy(),
                () -> new UUID(0, ids.incrementAndGet()));
    }

    private ExternalPlaceCandidate candidate(String externalId, String name, double lat, double lng) {
        return new ExternalPlaceCandidate(ExternalPlaceProvider.KAKAO, externalId, name, lat, lng);
    }

    private long count(String table) {
        return transactions.execute(connection -> {
            try (var statement = connection.prepareStatement("SELECT count(*) FROM onmaru." + table);
                 var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
    }
}

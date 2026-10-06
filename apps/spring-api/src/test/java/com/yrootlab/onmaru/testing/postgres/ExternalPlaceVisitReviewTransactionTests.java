package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.catalog.externalplace.ExternalPlaceCandidate;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlacePolicy;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlaceProvider;
import com.yrootlab.onmaru.community.command.review.CreateExternalPlaceVisitReviewCommand;
import com.yrootlab.onmaru.community.command.review.VisitReviewCommandService;
import com.yrootlab.onmaru.community.command.review.VisitReviewTagPolicy;
import com.yrootlab.onmaru.community.command.review.VisitReviewTransaction;
import com.yrootlab.onmaru.persistence.catalog.JdbcCatalogPublicPlaceIdStore;
import com.yrootlab.onmaru.persistence.catalog.JdbcExternalPlaceRegistry;
import com.yrootlab.onmaru.persistence.community.JdbcVisitReviewStore;
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
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalPlaceVisitReviewTransactionTests {

    private static final UUID MEMBER_ID = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");
    private static final UUID MISSING_MEMBER_ID = UUID.fromString("8ce13b1d-01bb-42ea-8457-5e0df299a5de");
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private DriverManagerDataSource dataSource;
    private JdbcTransactionRunner transactions;

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
        insertMember(MEMBER_ID);
    }

    @Test
    void commitsExternalPlaceAndReviewTogether() {
        service().createExternal(MEMBER_ID, command("success"));

        assertThat(count("catalog_place_identity")).isOne();
        assertThat(count("catalog_place_public_ids")).isOne();
        assertThat(count("catalog_external_places")).isOne();
        assertThat(count("community_visit_reviews")).isOne();
    }

    @Test
    void reviewForeignKeyFailureRollsBackAllExternalPlaceRows() {
        assertThatThrownBy(() -> service().createExternal(MISSING_MEMBER_ID, command("rollback")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("catalog_place_identity")).isZero();
        assertThat(count("catalog_place_public_ids")).isZero();
        assertThat(count("catalog_external_places")).isZero();
        assertThat(count("community_visit_reviews")).isZero();
    }

    private VisitReviewCommandService service() {
        var policy = new ExternalPlacePolicy();
        var publicIds = new JdbcCatalogPublicPlaceIdStore(dataSource, transactions);
        var store = new JdbcVisitReviewStore(dataSource, publicIds, transactions);
        var registry = new JdbcExternalPlaceRegistry(transactions, policy,
                () -> UUID.fromString("00000000-0000-0000-0000-000000000643"));
        return new VisitReviewCommandService(
                store,
                ignored -> Optional.empty(),
                policy,
                registry,
                ignored -> "kr-unassigned",
                transaction(),
                () -> UUID.fromString("00000000-0000-0000-0000-000000000118"),
                ignored -> Map.of(),
                new VisitReviewTagPolicy(),
                Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC));
    }

    private VisitReviewTransaction transaction() {
        return new VisitReviewTransaction() {
            @Override
            public <T> T execute(Supplier<T> operation) {
                return transactions.execute(ignored -> operation.get());
            }
        };
    }

    private CreateExternalPlaceVisitReviewCommand command(String externalId) {
        return new CreateExternalPlaceVisitReviewCommand(
                new ExternalPlaceCandidate(ExternalPlaceProvider.KAKAO, externalId, "대청댐", 36.4952, 127.4981),
                "경치가 좋았어요.", "한적", 4, List.of("#힐링"));
    }

    private void insertMember(UUID memberId) {
        transactions.execute(connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO onmaru.identity_members (id, status, created_at) VALUES (?, 'ACTIVE', ?)")) {
                statement.setObject(1, memberId);
                statement.setObject(2, OffsetDateTime.ofInstant(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC));
                statement.executeUpdate();
                return null;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
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

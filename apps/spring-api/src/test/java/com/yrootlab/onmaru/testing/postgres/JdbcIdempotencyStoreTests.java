package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.persistence.catalog.JdbcCatalogPublicPlaceIdStore;
import com.yrootlab.onmaru.persistence.community.JdbcVisitReviewStore;
import com.yrootlab.onmaru.persistence.jdbc.JdbcTransactionRunner;
import com.yrootlab.onmaru.persistence.web.JdbcIdempotencyStore;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyCommand;
import com.yrootlab.onmaru.web.common.idempotency.IdempotentResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcIdempotencyStoreTests {
    static final GenericContainer<?> DB = new GenericContainer<>(DockerImageName.parse("postgis/postgis:17-3.5-alpine")).withExposedPorts(5432).withEnv("POSTGRES_DB","onmaru_test").withEnv("POSTGRES_USER","onmaru_test").withEnv("POSTGRES_PASSWORD","onmaru_test").waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n",2));
    private DriverManagerDataSource ds;
    @BeforeAll static void start(){DB.start();} @AfterAll static void stop(){DB.stop();}
    @BeforeEach void reset() throws Exception {var url=url();try(var c=DriverManager.getConnection(url,"onmaru_test","onmaru_test")){PostgresTestDatabase.reset(c);} Flyway.configure().dataSource(url,"onmaru_test","onmaru_test").locations("classpath:db/migration/baseline").baselineOnMigrate(true).baselineVersion("0").load().migrate();ds=new DriverManagerDataSource(url,"onmaru_test","onmaru_test");}
    @Test void replaysStoredJsonResponseAcrossStoreInstances(){var command=new IdempotencyCommand(UUID.randomUUID(),"member-1","POST","/api/v1/places/p/reviews","hash"); var expected=IdempotentResponse.created("/reviews/1",Map.of("id","1")); var first=new JdbcIdempotencyStore(ds).execute(command, Clock.systemUTC(),()->expected); var replay=new JdbcIdempotencyStore(ds).execute(command,Clock.systemUTC(),()->IdempotentResponse.ok(Map.of("wrong",true))); assertThat(first).isEqualTo(expected);assertThat(replay).isEqualTo(expected);}

    @Test
    void rollsBackReviewAndReceiptTogetherThenAllowsACompleteRetry() throws Exception {
        var catalogPlaceId = UUID.randomUUID();
        var authorId = UUID.randomUUID();
        seedPlaceAndMember(catalogPlaceId, authorId);
        var publicPlaceIds = new JdbcCatalogPublicPlaceIdStore(ds);
        publicPlaceIds.register("p-jeonju-hanok-village", catalogPlaceId);
        var transactions = new JdbcTransactionRunner(ds);
        var reviews = new JdbcVisitReviewStore(ds, publicPlaceIds, transactions);
        var receipts = new JdbcIdempotencyStore(ds, transactions);
        var command = new IdempotencyCommand(
                UUID.randomUUID(), authorId.toString(), "POST", "/api/v1/places/p-jeonju-hanok-village/reviews", "hash");
        var review = new VisitReviewProjection(
                UUID.randomUUID(), "p-jeonju-hanok-village", "전주 한옥마을", "kr-45-jeonju",
                35.8151, 127.1530, "처마 아래에서 쉬기 좋았습니다.", "한적", 5, List.of("고즈넉함"),
                Instant.parse("2026-09-26T00:00:00Z"), authorId, Set.of(), VisitReviewStatus.PUBLISHED);

        assertThatThrownBy(() -> receipts.execute(command, Clock.systemUTC(), () -> {
            reviews.add(review);
            throw new IllegalStateException("force rollback after review insert");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(new JdbcVisitReviewStore(ds, publicPlaceIds).findSnapshot()).isEmpty();
        assertThat(receiptCount()).isZero();

        var response = receipts.execute(command, Clock.systemUTC(), () -> {
            reviews.add(review);
            return IdempotentResponse.created("/reviews/" + review.id(), Map.of("id", review.id().toString()));
        });
        assertThat(response.status()).isEqualTo(201);
        assertThat(new JdbcVisitReviewStore(ds, publicPlaceIds).findSnapshot()).containsExactly(review);
        assertThat(receiptCount()).isOne();
    }

    @Test
    void executesOnlyOneHandlerWhenTheSameReceiptIsRequestedConcurrently() throws Exception {
        var command = new IdempotencyCommand(
                UUID.randomUUID(), "member-1", "POST", "/api/v1/places/p/reviews", "hash");
        var firstHandlerStarted = new CountDownLatch(1);
        var handlersStarted = new CountDownLatch(2);
        var releaseHandler = new CountDownLatch(1);
        var executions = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> new JdbcIdempotencyStore(ds).execute(command, Clock.systemUTC(), () -> {
                executions.incrementAndGet();
                handlersStarted.countDown();
                firstHandlerStarted.countDown();
                await(releaseHandler);
                return IdempotentResponse.created("/reviews/1", Map.of("id", "1"));
            }));
            assertThat(firstHandlerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> new JdbcIdempotencyStore(ds).execute(command, Clock.systemUTC(), () -> {
                executions.incrementAndGet();
                handlersStarted.countDown();
                await(releaseHandler);
                return IdempotentResponse.created("/reviews/1", Map.of("id", "1"));
            }));
            assertThat(handlersStarted.await(500, TimeUnit.MILLISECONDS)).isFalse();
            releaseHandler.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(IdempotentResponse.created("/reviews/1", Map.of("id", "1")));
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(IdempotentResponse.created("/reviews/1", Map.of("id", "1")));
            assertThat(executions).hasValue(1);
        } finally {
            releaseHandler.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void seedPlaceAndMember(UUID placeId, UUID memberId) throws Exception {
        try (var connection = ds.getConnection()) {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO onmaru.catalog_place_identity (id, created_at) VALUES (?, ?)")) {
                statement.setObject(1, placeId);
                statement.setObject(2, OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC));
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "INSERT INTO onmaru.identity_members (id, status, created_at) VALUES (?, 'ACTIVE', ?)")) {
                statement.setObject(1, memberId);
                statement.setObject(2, OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC));
                statement.executeUpdate();
            }
        }
    }

    private long receiptCount() throws Exception {
        try (var connection = ds.getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM onmaru.web_idempotency_receipts");
             var result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    static String url(){return "jdbc:postgresql://"+DB.getHost()+":"+DB.getMappedPort(5432)+"/onmaru_test";}
}

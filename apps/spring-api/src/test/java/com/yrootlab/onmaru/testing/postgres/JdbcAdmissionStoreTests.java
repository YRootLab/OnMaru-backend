package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.operations.admission.AdmissionDecision;
import com.yrootlab.onmaru.operations.admission.AdmissionPolicy;
import com.yrootlab.onmaru.operations.admission.AdmissionRejectionReason;
import com.yrootlab.onmaru.operations.admission.AdmissionRequest;
import com.yrootlab.onmaru.operations.admission.AdmissionService;
import com.yrootlab.onmaru.operations.admission.AdmissionSubject;
import com.yrootlab.onmaru.operations.admission.OperationBudget;
import com.yrootlab.onmaru.operations.admission.SubjectType;
import com.yrootlab.onmaru.persistence.operations.admission.JdbcAdmissionStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAdmissionStoreTests {

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

    private DataSource dataSource;
    private AdmissionPolicy policy;
    private AdmissionRequest request;

    @BeforeAll
    static void startPostgres() {
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @BeforeEach
    void resetDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD)) {
            PostgresTestDatabase.reset(connection);
        }
        Flyway.configure()
                .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                .locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(jdbcUrl(), USERNAME, PASSWORD);
        policy = new AdmissionPolicy(Duration.ofMinutes(1), List.of(
                new OperationBudget("journey.ai", SubjectType.MEMBER, 5, Duration.ofDays(1))
        ));
        request = new AdmissionRequest(
                "journey.ai",
                new AdmissionSubject(SubjectType.MEMBER, "member-1")
        );
    }

    @Test
    void concurrentDailyAdmissionApprovesNoMoreThanLimitAndAuditsEveryAttempt() throws Exception {
        var service = new AdmissionService(
                new JdbcAdmissionStore(dataSource),
                Clock.fixed(Instant.parse("2026-09-15T14:59:59Z"), ZoneOffset.UTC)
        );
        var results = Collections.synchronizedList(new ArrayList<AdmissionDecision>());
        var ready = new CountDownLatch(20);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(20)) {
            for (int index = 0; index < 20; index++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    results.add(service.admit(request, policy));
                    return null;
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(results).hasSize(20);
        assertThat(results.stream().filter(AdmissionDecision::allowed)).hasSize(5);
        assertThat(results.stream().filter(decision -> !decision.allowed())).hasSize(15);
        assertThat(results.stream()
                .filter(decision -> !decision.allowed())
                .map(AdmissionDecision::retryAfter)
                .distinct()).containsExactly(Duration.ofSeconds(1));
        assertThat(count("SELECT consumed FROM onmaru.operations_admission WHERE scope_key = 'journey.ai:MEMBER:member-1'"))
                .isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM onmaru.operations_admission_audit")).isEqualTo(20);
        assertThat(count("""
                SELECT COUNT(*)
                FROM onmaru.operations_admission_audit
                WHERE operation = 'journey.ai' AND subject_type = 'MEMBER' AND decision = 'ALLOWED'
                """)).isEqualTo(5);
        assertThat(count("""
                SELECT COUNT(*)
                FROM onmaru.operations_admission_audit
                WHERE operation = 'journey.ai' AND subject_type = 'MEMBER' AND decision = 'REJECTED'
                """)).isEqualTo(15);
    }

    @Test
    void kstMidnightStartsNewPersistentWindow() {
        var beforeKstMidnight = new AdmissionService(
                new JdbcAdmissionStore(dataSource),
                Clock.fixed(Instant.parse("2026-09-15T14:59:59Z"), ZoneOffset.UTC)
        );
        var afterKstMidnight = new AdmissionService(
                new JdbcAdmissionStore(dataSource),
                Clock.fixed(Instant.parse("2026-09-15T15:00:00Z"), ZoneOffset.UTC)
        );
        var onePerDay = new AdmissionPolicy(Duration.ofMinutes(1), List.of(
                new OperationBudget("journey.ai", SubjectType.MEMBER, 1, Duration.ofDays(1))
        ));

        assertThat(beforeKstMidnight.admit(request, onePerDay).allowed()).isTrue();
        assertThat(beforeKstMidnight.admit(request, onePerDay).retryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(afterKstMidnight.admit(request, onePerDay).allowed()).isTrue();
        assertThat(count("SELECT consumed FROM onmaru.operations_admission WHERE scope_key = 'journey.ai:MEMBER:member-1'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM onmaru.operations_admission_audit")).isEqualTo(3);
    }

    @Test
    void concurrentActiveAdmissionApprovesOneSlotAndReleaseReturnsCapacityWithoutBurningRejectedQuota()
            throws Exception {
        var service = new AdmissionService(
                new JdbcAdmissionStore(dataSource),
                Clock.fixed(Instant.parse("2026-09-15T03:00:05Z"), ZoneOffset.UTC)
        );
        var activePolicy = new AdmissionPolicy(Duration.ofMinutes(1), List.of(
                new OperationBudget("journey.ai", SubjectType.MEMBER, 2, Duration.ofDays(1), 1)
        ));
        var results = Collections.synchronizedList(new ArrayList<AdmissionDecision>());
        var ready = new CountDownLatch(10);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(10)) {
            for (int index = 0; index < 10; index++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    results.add(service.admitActive(request, activePolicy));
                    return null;
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(results).hasSize(10);
        assertThat(results.stream().filter(AdmissionDecision::allowed)).hasSize(1);
        assertThat(results.stream()
                .filter(decision -> !decision.allowed())
                .map(AdmissionDecision::reason)
                .distinct()).containsExactly(AdmissionRejectionReason.ACTIVE_LIMIT);
        assertThat(count("SELECT active_count FROM onmaru.operations_admission WHERE scope_key = 'journey.ai:MEMBER:member-1'"))
                .isEqualTo(1);
        assertThat(count("SELECT consumed FROM onmaru.operations_admission WHERE scope_key = 'journey.ai:MEMBER:member-1'"))
                .isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*)
                FROM onmaru.operations_admission_audit
                WHERE decision = 'REJECTED' AND reason = 'ACTIVE_LIMIT'
                """)).isEqualTo(9);

        service.releaseActive(request, activePolicy);
        assertThat(count("SELECT active_count FROM onmaru.operations_admission WHERE scope_key = 'journey.ai:MEMBER:member-1'"))
                .isZero();
        assertThat(service.admitActive(request, activePolicy).allowed()).isTrue();
        service.releaseActive(request, activePolicy);
        var quotaRejected = service.admitActive(request, activePolicy);
        assertThat(quotaRejected.allowed()).isFalse();
        assertThat(quotaRejected.reason()).isEqualTo(AdmissionRejectionReason.QUOTA_EXCEEDED);
    }

    private int count(String sql) {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(), postgres.getMappedPort(POSTGRES_PORT), DATABASE);
    }

    private record DriverManagerDataSource(String url, String username, String password) implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public Connection getConnection(String suppliedUsername, String suppliedPassword) throws SQLException {
            return DriverManager.getConnection(url, suppliedUsername, suppliedPassword);
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return DriverManager.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            DriverManager.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            DriverManager.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return DriverManager.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }
}

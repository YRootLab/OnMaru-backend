package com.yrootlab.onmaru.testing.postgres;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcTransactionRunnerTests {
    private static final GenericContainer<?> DB = new GenericContainer<>(DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432).withEnv("POSTGRES_DB", "onmaru_test").withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test").waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
    private DriverManagerDataSource dataSource;

    @BeforeAll static void start() { DB.start(); }
    @AfterAll static void stop() { DB.stop(); }

    @BeforeEach void reset() throws Exception {
        var url = url();
        try (var connection = DriverManager.getConnection(url, "onmaru_test", "onmaru_test")) { PostgresTestDatabase.reset(connection); }
        Flyway.configure().dataSource(url, "onmaru_test", "onmaru_test").locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        dataSource = new DriverManagerDataSource(url, "onmaru_test", "onmaru_test");
    }

    @Test
    void rollsBackEveryWriteWhenTheEnclosingOperationFails() {
        var runner = new JdbcTransactionRunner(dataSource);

        assertThatThrownBy(() -> runner.execute(connection -> {
            try (var statement = connection.prepareStatement("INSERT INTO onmaru.web_idempotency_receipts (subject_id, idempotency_key, method, path, payload_fingerprint, response_status, response_headers, response_body, created_at) VALUES ('member', gen_random_uuid(), 'POST', '/x', 'x', 201, '{}'::jsonb, '{}'::jsonb, now())")) {
                statement.executeUpdate();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        Long receiptCount = runner.execute(connection -> {
            try (var statement = connection.prepareStatement("SELECT count(*) FROM onmaru.web_idempotency_receipts"); var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        assertThat(receiptCount).isZero();
    }

    private static String url() { return "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/onmaru_test"; }
}

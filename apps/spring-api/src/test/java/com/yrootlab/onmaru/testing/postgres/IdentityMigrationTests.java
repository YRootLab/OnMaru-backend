package com.yrootlab.onmaru.testing.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityMigrationTests {

    private static final int POSTGRES_PORT = 5432;
    private static final String DATABASE = "onmaru_test";
    private static final String USERNAME = "onmaru_test";
    private static final String PASSWORD = "onmaru_test";
    private static final String ACTIVE_TOKEN_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String EXPIRED_TOKEN_HASH =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String REVOKED_TOKEN_HASH =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    private static final GenericContainer<?> postgres = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(POSTGRES_PORT)
            .withEnv("POSTGRES_DB", DATABASE)
            .withEnv("POSTGRES_USER", USERNAME)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @BeforeAll
    static void startPostgres() {
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @Test
    void migratesMemberSessionGuestGrantSchema() throws Exception {
        resetAndMigrate();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'onmaru'
                      AND table_name IN (
                        'identity_members',
                        'identity_external_accounts',
                        'identity_sessions',
                        'identity_guests',
                        'identity_oauth_states',
                        'identity_exploration_grants',
                        'identity_deletion_ledger'
                      )
                    """)).isEqualTo(7);
        }
    }

    @Test
    void preventsDuplicateExternalAccountSoFirstLoginCreatesOneMember() throws Exception {
        resetAndMigrate();
        var firstMember = UUID.randomUUID();
        var secondMember = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertMember(statement, firstMember);
            insertMember(statement, secondMember);
            statement.execute("""
                    INSERT INTO onmaru.identity_external_accounts (
                        id, member_id, provider, issuer, subject, created_at
                    ) VALUES (
                        gen_random_uuid(), '%s', 'KAKAO', 'https://kauth.kakao.com', 'subject-1',
                        '2026-09-15T00:00:00Z'
                    )
                    """.formatted(firstMember));

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO onmaru.identity_external_accounts (
                        id, member_id, provider, issuer, subject, created_at
                    ) VALUES (
                        gen_random_uuid(), '%s', 'KAKAO', 'https://kauth.kakao.com', 'subject-1',
                        '2026-09-15T00:00:01Z'
                    )
                    """.formatted(secondMember)))
                    .hasMessageContaining("identity_external_accounts_provider_issuer_subject_uq");

            assertThat(countRows(statement, """
                    SELECT COUNT(DISTINCT member_id)
                    FROM onmaru.identity_external_accounts
                    WHERE provider = 'KAKAO'
                      AND issuer = 'https://kauth.kakao.com'
                      AND subject = 'subject-1'
                    """)).isEqualTo(1);
        }
    }

    @Test
    void validSessionViewExcludesExpiredAndRevokedTokens() throws Exception {
        resetAndMigrate();
        var member = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertMember(statement, member);
            statement.execute("""
                    INSERT INTO onmaru.identity_sessions (
                        token_hash, member_id, created_at, last_seen_at, absolute_expires_at, revoked_at
                    ) VALUES
                        ('%s', '%s', '2026-09-15T00:00:00Z', '2026-09-15T00:00:00Z',
                         CURRENT_TIMESTAMP + interval '1 hour', NULL),
                        ('%s', '%s', '2026-09-15T00:00:00Z', '2026-09-15T00:00:00Z',
                         CURRENT_TIMESTAMP - interval '1 minute', NULL),
                        ('%s', '%s', '2026-09-15T00:00:00Z', '2026-09-15T00:00:00Z',
                         CURRENT_TIMESTAMP + interval '1 hour', CURRENT_TIMESTAMP)
                    """.formatted(
                    ACTIVE_TOKEN_HASH,
                    member,
                    EXPIRED_TOKEN_HASH,
                    member,
                    REVOKED_TOKEN_HASH,
                    member));

            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.identity_valid_sessions"))
                    .isEqualTo(1);
            assertThat(stringValue(statement, "SELECT token_hash FROM onmaru.identity_valid_sessions"))
                    .isEqualTo(ACTIVE_TOKEN_HASH);
        }
    }

    @Test
    void storesOnlyTokenHashesAndRejectsRawTokenLookingValues() throws Exception {
        resetAndMigrate();
        var member = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertMember(statement, member);

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO onmaru.identity_sessions (
                        token_hash, member_id, created_at, last_seen_at, absolute_expires_at
                    ) VALUES (
                        'plain-token-value', '%s', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + interval '1 hour'
                    )
                    """.formatted(member)))
                    .hasMessageContaining("identity_sessions_token_hash_format_ck");
        }
    }

    private static void insertMember(java.sql.Statement statement, UUID memberId) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.identity_members (id, status, created_at)
                VALUES ('%s', 'ACTIVE', '2026-09-15T00:00:00Z')
                """.formatted(memberId));
    }

    private static void resetAndMigrate() throws Exception {
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
    }

    private static int countRows(java.sql.Statement statement, String sql) throws Exception {
        try (var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static String stringValue(java.sql.Statement statement, String sql) throws Exception {
        try (var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(),
                postgres.getMappedPort(POSTGRES_PORT),
                DATABASE);
    }
}

package com.yrootlab.onmaru.testing.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.sql.SQLException;
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
    void preventsGuestExplorationGrantReplayAcrossMembers() throws Exception {
        resetAndMigrate();
        var firstMember = UUID.randomUUID();
        var secondMember = UUID.randomUUID();
        var guestId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertMember(statement, firstMember);
            insertMember(statement, secondMember);
            insertGuest(statement, guestId);
            insertGuestExploration(statement, explorationId, guestId);
            statement.execute("""
                    INSERT INTO onmaru.identity_exploration_grants (
                        member_id, exploration_id, expires_at
                    ) VALUES (
                        '%s', '%s', CURRENT_TIMESTAMP + interval '10 minutes'
                    )
                    """.formatted(firstMember, explorationId));

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO onmaru.identity_exploration_grants (
                        member_id, exploration_id, expires_at
                    ) VALUES (
                        '%s', '%s', CURRENT_TIMESTAMP + interval '10 minutes'
                    )
                    """.formatted(secondMember, explorationId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("identity_exploration_grants_exploration_id_uq");
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

    @Test
    void memberProfilesBackfillExistingMembersAndEnforceCatalog() throws Exception {
        resetAndMigrateThrough("039");
        var member = UUID.fromString("55200000-0000-0000-0000-000000000001");

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertMember(statement, member);
        }

        migrateThrough("040");

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, """
                    SELECT COUNT(*) FROM onmaru.identity_member_profiles
                    WHERE member_id = '%s'
                    """.formatted(member))).isEqualTo(1);

            var displayName = stringValue(statement, """
                    SELECT display_name FROM onmaru.identity_member_profiles
                    WHERE member_id = '%s'
                    """.formatted(member));
            assertThat(displayName.codePointCount(0, displayName.length())).isBetween(2, 20);
            assertThat(stringValue(statement, """
                    SELECT character_id FROM onmaru.identity_member_profiles
                    WHERE member_id = '%s'
                    """.formatted(member))).matches("CHARACTER_(0[1-9]|10)");
            assertThat(stringValue(statement, """
                    SELECT background_id FROM onmaru.identity_member_profiles
                    WHERE member_id = '%s'
                    """.formatted(member))).matches("BACKGROUND_(0[1-9]|10)");

            assertThatThrownBy(() -> statement.execute("""
                    UPDATE onmaru.identity_member_profiles
                    SET character_id = 'CHARACTER_11' WHERE member_id = '%s'
                    """.formatted(member)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("identity_member_profiles_character_id_ck");
            assertThatThrownBy(() -> statement.execute("""
                    UPDATE onmaru.identity_member_profiles
                    SET background_id = 'BACKGROUND_00' WHERE member_id = '%s'
                    """.formatted(member)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("identity_member_profiles_background_id_ck");
            assertThatThrownBy(() -> statement.execute("""
                    UPDATE onmaru.identity_member_profiles
                    SET display_name = '   ' WHERE member_id = '%s'
                    """.formatted(member)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("identity_member_profiles_display_name_ck");
            assertThatThrownBy(() -> statement.execute("""
                    UPDATE onmaru.identity_member_profiles
                    SET updated_at = created_at - interval '1 second' WHERE member_id = '%s'
                    """.formatted(member)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("identity_member_profiles_updated_at_ck");

            statement.execute("DELETE FROM onmaru.identity_members WHERE id = '%s'".formatted(member));
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.identity_member_profiles")).isZero();
        }
    }

    @Test
    void memberProfileNicknamesBecomeUniqueWithoutFailingOnExistingDuplicates() throws Exception {
        resetAndMigrateThrough("041");
        var first = UUID.fromString("64000000-0000-0000-0000-000000000001");
        var second = UUID.fromString("64000000-0000-0000-0000-000000000002");
        var existingLegacyFallback = UUID.fromString("64000000-0000-0000-0000-000000000003");
        var existingFirstCandidate = UUID.fromString("64000000-0000-0000-0000-000000000004");

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertMember(statement, first);
            insertMember(statement, second);
            insertMember(statement, existingLegacyFallback);
            insertMember(statement, existingFirstCandidate);
            statement.execute("""
                    INSERT INTO onmaru.identity_member_profiles
                        (member_id, display_name, character_id, background_id, created_at, updated_at)
                    VALUES
                        ('%s', '같은 닉네임', 'CHARACTER_01', 'BACKGROUND_01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        ('%s', '같은 닉네임', 'CHARACTER_02', 'BACKGROUND_02', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        ('%s', '익명-' || substring(md5('%s'), 1, 17),
                         'CHARACTER_03', 'BACKGROUND_03', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                        ('%s', '익명-' || substring(md5('%s:0'), 1, 17),
                         'CHARACTER_04', 'BACKGROUND_04', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(
                            first,
                            second,
                            existingLegacyFallback,
                            second,
                            existingFirstCandidate,
                            second));
        }

        migrateThrough("042");

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, """
                    SELECT COUNT(DISTINCT display_name)
                    FROM onmaru.identity_member_profiles
                    WHERE member_id IN ('%s', '%s', '%s', '%s')
                    """.formatted(first, second, existingLegacyFallback, existingFirstCandidate))).isEqualTo(4);
            assertThatThrownBy(() -> statement.execute("""
                    UPDATE onmaru.identity_member_profiles
                    SET display_name = '같은 닉네임'
                    WHERE member_id = '%s'
                    """.formatted(second)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("identity_member_profiles_display_name_uq");
        }
    }

    private static void insertMember(java.sql.Statement statement, UUID memberId) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.identity_members (id, status, created_at)
                VALUES ('%s', 'ACTIVE', '2026-09-15T00:00:00Z')
                """.formatted(memberId));
    }

    private static void insertGuest(java.sql.Statement statement, UUID guestId) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.identity_guests (id, token_hash, expires_at)
                VALUES (
                    '%s',
                    'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                    CURRENT_TIMESTAMP + interval '1 hour'
                )
                """.formatted(guestId));
    }

    private static void insertGuestExploration(
            java.sql.Statement statement,
            UUID explorationId,
            UUID guestId
    ) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.discovery_explorations (
                    id, owner_guest_id, state_version, pinned_refs, excluded_refs,
                    created_at, updated_at
                ) VALUES (
                    '%s', '%s', 0, '[]'::jsonb, '[]'::jsonb,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """.formatted(explorationId, guestId));
    }

    private static void resetAndMigrate() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD)) {
            PostgresTestDatabase.reset(connection);
        }
        migrateThrough(null);
    }

    private static void resetAndMigrateThrough(String target) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD)) {
            PostgresTestDatabase.reset(connection);
        }
        migrateThrough(target);
    }

    private static void migrateThrough(String target) {
        var configuration = Flyway.configure()
                .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                .locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true)
                .baselineVersion("0");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
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

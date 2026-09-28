package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.operations.retention.RetentionCleanupPolicy;
import com.yrootlab.onmaru.persistence.web.JdbcIdempotencyStore;
import com.yrootlab.onmaru.persistence.operations.retention.JdbcRetentionCleanupStore;
import com.yrootlab.onmaru.persistence.stamp.JdbcStampRankingStore;
import com.yrootlab.onmaru.persistence.stamp.JdbcStampStore;
import com.yrootlab.onmaru.stamp.VerifiedPlace;
import com.yrootlab.onmaru.stamp.ranking.StampRankingIdentity;
import com.yrootlab.onmaru.stamp.ranking.StampRankingNicknameType;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyCommand;
import com.yrootlab.onmaru.web.common.idempotency.IdempotentResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetentionCleanupJdbcTests {

    private static final int POSTGRES_PORT = 5432;
    private static final String DATABASE = "onmaru_test";
    private static final String USERNAME = "onmaru_test";
    private static final String PASSWORD = "onmaru_test";
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

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
    void cleanupIsBatchBoundedAndRestartSafeAtTtlBoundary() throws Exception {
        resetAndMigrate();
        var memberId = UUID.randomUUID();
        var expiredGuestId = UUID.randomUUID();
        var freshGuestId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var proposalId = UUID.randomUUID();
        var inactiveRevisionId = UUID.randomUUID();
        var activeRevisionId = UUID.randomUUID();
        var savedResourceId = UUID.randomUUID();
        var stampPlaceId = UUID.randomUUID();
        seedCleanupFixtures(
                memberId,
                expiredGuestId,
                freshGuestId,
                explorationId,
                runId,
                proposalId,
                inactiveRevisionId,
                activeRevisionId,
                savedResourceId,
                stampPlaceId);
        var store = new JdbcRetentionCleanupStore(dataSource());
        var policy = new RetentionCleanupPolicy(
                1,
                Duration.ofDays(30),
                Duration.ofDays(1),
                Duration.ofHours(1),
                Duration.ofDays(7),
                Duration.ofDays(14));

        var first = store.cleanup(policy, NOW);
        var replay = store.cleanup(policy, NOW);
        var settledReplay = store.cleanup(policy, NOW);

        assertThat(first.expiredGuests()).isEqualTo(1);
        assertThat(first.expiredSessions()).isEqualTo(1);
        assertThat(first.expiredRuns()).isEqualTo(1);
        assertThat(first.expiredProposals()).isEqualTo(1);
        assertThat(first.inactiveRevisions()).isEqualTo(1);
        assertThat(first.memberDeletionResources()).isEqualTo(5);
        assertThat(replay.memberDeletionResources()).isEqualTo(2);
        assertThat(replay.ledgerEntries()).isEqualTo(2);
        assertThat(settledReplay.ledgerEntries()).isZero();
        assertRemainingRows(activeRevisionId, freshGuestId, memberId);

        var dataSource = dataSource();
        assertThatThrownBy(() -> new JdbcStampRankingStore(dataSource).participate(
                memberId,
                new StampRankingIdentity(UUID.randomUUID(), "익명 유람객 9999", "익명 유람객 9999",
                        StampRankingNicknameType.GENERATED),
                NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JdbcStampStore(dataSource).record(
                memberId, new VerifiedPlace(stampPlaceId, "p-cleanup-stamp", "kr-11-jongno", 10),
                NOW.plusSeconds(1), 10))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keepsOnlyActivePublishedRevisionAndRetainsOnlyRecentInProgressSnapshot() throws Exception {
        resetAndMigrate();
        var oldestPublished = UUID.randomUUID();
        var previousPublished = UUID.randomUUID();
        var activePublished = UUID.randomUUID();
        var failed = UUID.randomUUID();
        var staging = UUID.randomUUID();
        var recentStaging = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.catalog_dataset_revisions (
                        id, dataset, status, base_revision_id, fetched_at, published_at
                    ) VALUES
                        ('%s', 'odii-audio', 'PUBLISHED', NULL,
                         '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z'),
                        ('%s', 'odii-audio', 'PUBLISHED', '%s',
                         '2026-09-02T00:00:00Z', '2026-09-02T00:00:00Z'),
                        ('%s', 'odii-audio', 'PUBLISHED', '%s',
                         '2026-09-03T00:00:00Z', '2026-09-03T00:00:00Z'),
                        ('%s', 'odii-audio', 'FAILED', '%s',
                         '2026-09-16T23:30:00Z', NULL),
                        ('%s', 'odii-audio', 'STAGING', '%s',
                         '2026-09-16T00:00:00Z', NULL),
                        ('%s', 'odii-audio', 'STAGING', '%s',
                         '2026-09-16T23:30:00Z', NULL)
                    """.formatted(
                    oldestPublished,
                    previousPublished, oldestPublished,
                    activePublished, previousPublished,
                    failed, activePublished,
                    staging, activePublished,
                    recentStaging, activePublished));
            statement.execute("""
                    INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                    VALUES ('odii-audio', '%s', '2026-09-03T00:00:00Z')
                    """.formatted(activePublished));
            statement.execute("""
                    INSERT INTO onmaru.audio_odii_spots (
                        id, provider, tid, tlid, lang_code, created_at
                    ) VALUES
                        ('00000000-0000-0000-0000-000000000101', 'ODII', 'old', 'old', 'ko', NOW()),
                        ('00000000-0000-0000-0000-000000000102', 'ODII', 'failed', 'failed', 'ko', NOW()),
                        ('00000000-0000-0000-0000-000000000103', 'ODII', 'staging', 'staging', 'ko', NOW())
                    """);
            statement.execute("""
                    INSERT INTO onmaru.audio_spot_versions (
                        revision_id, spot_id, title, status, hash
                    ) VALUES
                        ('%s', '00000000-0000-0000-0000-000000000101', 'old', 'ACTIVE', 'old'),
                        ('%s', '00000000-0000-0000-0000-000000000102', 'failed', 'ACTIVE', 'failed'),
                        ('%s', '00000000-0000-0000-0000-000000000103', 'staging', 'ACTIVE', 'staging')
                    """.formatted(oldestPublished, failed, staging));
        }

        var result = new JdbcRetentionCleanupStore(dataSource()).cleanup(
                new RetentionCleanupPolicy(
                        100,
                        Duration.ofDays(30),
                        Duration.ofDays(1),
                        Duration.ofHours(1),
                        Duration.ofDays(7),
                        Duration.ofHours(1)),
                NOW);

        assertThat(result.inactiveRevisions()).isEqualTo(4);
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.catalog_dataset_revisions"))
                    .isEqualTo(2);
            assertThat(countRows(statement, """
                    SELECT COUNT(*) FROM onmaru.catalog_dataset_revisions
                    WHERE id = '%s' AND base_revision_id IS NULL
                    """.formatted(activePublished))).isOne();
            assertThat(countRows(statement, """
                    SELECT COUNT(*) FROM onmaru.catalog_dataset_revisions
                    WHERE id = '%s' AND status = 'STAGING'
                    """.formatted(recentStaging))).isOne();
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.audio_spot_versions"))
                    .isZero();
        }
    }

    @Test
    void approvedStorageCleanupScriptKeepsActiveRevisionAndCompactRunMetadata() throws Exception {
        resetAndMigrate();
        var activeRevision = UUID.randomUUID();
        var failedRevision = UUID.randomUUID();
        var runId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.catalog_dataset_revisions (id, dataset, status, fetched_at, published_at)
                    VALUES
                      ('%s', 'kto-korean-tour', 'PUBLISHED', '2026-09-27T00:00:00Z', '2026-09-27T01:00:00Z'),
                      ('%s', 'kto-korean-tour', 'FAILED', '2026-09-28T00:00:00Z', NULL)
                    """.formatted(activeRevision, failedRevision));
            statement.execute("""
                    INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                    VALUES ('kto-korean-tour', '%s', '2026-09-27T01:00:00Z')
                    """.formatted(activeRevision));
            statement.execute("""
                    INSERT INTO onmaru.operations_sync_runs (
                        id, dataset, scheduled_for, attempt, status, revision_id, started_at, finished_at
                    ) VALUES ('%s', 'kto-korean-tour', '2026-09-28T00:00:00Z', 1,
                              'FAILED', '%s', '2026-09-28T00:00:00Z', '2026-09-28T00:01:00Z')
                    """.formatted(runId, failedRevision));
            statement.execute("""
                    INSERT INTO onmaru.operations_sync_quarantine (
                        run_id, record_key, error_code, payload_hash, redacted_payload, expires_at
                    ) VALUES ('%s', 'tourapi:1', 'INVALID_COORDINATES', repeat('a', 64),
                              '{"title":"bad"}'::jsonb, '2026-10-05T00:00:00Z')
                    """.formatted(runId));

            String cleanup = Files.readString(Path.of("../../scripts/operations/453-storage-cleanup.sql"));
            statement.execute(cleanup);

            assertThat(countRows(statement, "SELECT count(*) FROM onmaru.operations_sync_quarantine")).isZero();
            assertThat(countRows(statement, "SELECT count(*) FROM onmaru.catalog_dataset_revisions")).isOne();
            assertThat(countRows(statement, """
                    SELECT count(*) FROM onmaru.catalog_dataset_revisions WHERE id = '%s'
                    """.formatted(activeRevision))).isOne();
            assertThat(countRows(statement, """
                    SELECT count(*) FROM onmaru.operations_sync_runs
                    WHERE id = '%s' AND status = 'FAILED' AND revision_id IS NULL
                    """.formatted(runId))).isOne();
        }
    }

    private static void seedCleanupFixtures(
            UUID memberId,
            UUID expiredGuestId,
            UUID freshGuestId,
            UUID explorationId,
            UUID runId,
            UUID proposalId,
            UUID inactiveRevisionId,
            UUID activeRevisionId,
            UUID savedResourceId,
            UUID stampPlaceId
    ) throws Exception {
        var checkInId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.identity_members (id, status, created_at)
                    VALUES ('%s', 'ACTIVE', '2026-09-01T00:00:00Z')
                    """.formatted(memberId));
            statement.execute("""
                    INSERT INTO onmaru.identity_sessions (
                        token_hash, member_id, created_at, last_seen_at, absolute_expires_at, revoked_at
                    ) VALUES
                        ('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '%s',
                         '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z', '2026-09-15T23:59:59Z', NULL),
                        ('bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', '%s',
                         '2026-09-17T00:00:00Z', '2026-09-17T00:00:00Z', '2026-09-16T12:00:00Z', NULL)
                    """.formatted(memberId, memberId));
            statement.execute("""
                    INSERT INTO onmaru.identity_guests (id, token_hash, expires_at)
                    VALUES
                        ('%s', 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                         '2026-08-17T00:00:00Z'),
                        ('%s', 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                         '2026-08-18T00:00:01Z')
                    """.formatted(expiredGuestId, freshGuestId));
            statement.execute("""
                    INSERT INTO onmaru.discovery_explorations (
                        id, owner_member_id, state_version, pinned_refs, excluded_refs, created_at, updated_at
                    ) VALUES (
                        '%s', '%s', 0, '[]'::jsonb, '[]'::jsonb,
                        '2026-09-16T00:00:00Z', '2026-09-16T00:00:00Z'
                    )
                    """.formatted(explorationId, memberId));
            statement.execute("""
                    INSERT INTO onmaru.discovery_runs (
                        id, exploration_id, actor_key, base_version, status, stage, outcome,
                        created_at, deadline_at, started_at, generation, error_code, engine
                    ) VALUES (
                        '%s', '%s', 'member:%s', 0, 'FAILED', NULL, NULL,
                        '2026-09-16T00:00:00Z', '2026-09-16T22:59:59Z',
                        '2026-09-16T00:00:01Z', 1, 'RUN_DEADLINE_EXCEEDED', 'baseline'
                    )
                    """.formatted(runId, explorationId, memberId));
            statement.execute("""
                    INSERT INTO onmaru.discovery_proposals (
                        id, run_id, exploration_id, base_version, ordered_refs, reasons,
                        evidence, expires_at, status
                    ) VALUES (
                        '%s', '%s', '%s', 0, '[]'::jsonb, '{}'::jsonb,
                        '{}'::jsonb, '2026-09-09T23:59:59Z', 'INVALIDATED'
                    )
                    """.formatted(proposalId, runId, explorationId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_dataset_revisions (
                        id, dataset, status, fetched_at, published_at
                    ) VALUES
                        ('%s', 'odii', 'STAGING', '2026-09-01T00:00:00Z', NULL),
                        ('%s', 'odii', 'PUBLISHED', '2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z')
                    """.formatted(inactiveRevisionId, activeRevisionId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                    VALUES ('odii', '%s', '2026-09-02T00:00:00Z')
                    """.formatted(activeRevisionId));
            statement.execute("""
                    INSERT INTO onmaru.journey_saved_resources (
                        id, member_id, resource_type, resource_id, saved_at
                    ) VALUES (
                        gen_random_uuid(), '%s', 'PLACE', '%s', '2026-09-16T00:00:00Z'
                    )
                    """.formatted(memberId, savedResourceId));
            statement.execute("""
                    INSERT INTO onmaru.journey_saved_journeys (
                        id, member_id, source_exploration_id, source_version, saved_at,
                        title, snapshot, snapshot_hash
                    ) VALUES (
                        gen_random_uuid(), '%s', '%s', 0, '2026-09-16T00:00:00Z',
                        'saved', '{}'::jsonb, 'hash'
                    )
                    """.formatted(memberId, explorationId));
            statement.execute("""
                    INSERT INTO onmaru.catalog_place_identity (id, created_at)
                    VALUES ('%s', '2026-09-01T00:00:00Z')
                    """.formatted(stampPlaceId));
            statement.execute("""
                    INSERT INTO onmaru.stamp_check_ins (
                        id, member_id, place_id, public_place_id, region_code, checked_in_at,
                        check_in_bucket, distance_meters, accuracy_meters
                    ) VALUES (
                        '%s', '%s', '%s', 'p-cleanup-stamp', 'kr-11-jongno',
                        '2026-09-16T00:00:00Z', '2026-09-16T00:00:00Z', 10, 10
                    )
                    """.formatted(checkInId, memberId, stampPlaceId));
            statement.execute("""
                    INSERT INTO onmaru.stamp_awards (
                        id, member_id, stamp_code, trigger_check_in_id, awarded_at
                    ) VALUES
                        (gen_random_uuid(), '%s', 'stamp_bukchon', '%s', '2026-09-16T00:00:00Z'),
                        (gen_random_uuid(), '%s', 'stamp_night_hanok', '%s', '2026-09-16T00:00:01Z')
                    """.formatted(memberId, checkInId, memberId, checkInId));
            statement.execute("""
                    INSERT INTO onmaru.stamp_ranking_profiles (
                        member_id, ranking_public_id, public_nickname, nickname_normalized,
                        nickname_type, participating, consented_at, created_at, updated_at
                    ) VALUES (
                        '%s', gen_random_uuid(), '익명 유람객 0262', '익명 유람객 0262',
                        'GENERATED', true, '2026-09-16T00:00:00Z',
                        '2026-09-16T00:00:00Z', '2026-09-16T00:00:00Z'
                    )
                    """.formatted(memberId));
        }

        new JdbcIdempotencyStore(dataSource()).execute(
                new IdempotencyCommand(
                        UUID.randomUUID(), memberId.toString(), "POST",
                        "/api/v1/places/p-cleanup-stamp/check-ins", "cleanup-receipt"),
                Clock.fixed(Instant.parse("2026-09-16T00:00:02Z"), ZoneOffset.UTC),
                () -> IdempotentResponse.created(
                        "/api/v1/check-ins/" + checkInId,
                        Map.of(
                                "placeId", "p-cleanup-stamp",
                                "checkedInAt", "2026-09-16T00:00:00Z",
                                "distanceMeters", 10,
                                "awardedStampCodes", java.util.List.of("stamp_bukchon", "stamp_night_hanok"))));

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("UPDATE onmaru.identity_members SET status = 'DELETING' WHERE id = '%s'"
                    .formatted(memberId));
            statement.execute("""
                    INSERT INTO onmaru.identity_deletion_ledger (member_id, requested_at, status, reason)
                    VALUES ('%s', '2026-09-16T00:00:00Z', 'REQUESTED', 'USER_REQUESTED')
                    """.formatted(memberId));
        }
    }

    private static void assertRemainingRows(UUID activeRevisionId, UUID freshGuestId, UUID memberId) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.identity_sessions"))
                    .isEqualTo(1);
            assertThat(countRows(statement, """
                    SELECT COUNT(*) FROM onmaru.identity_guests WHERE id = '%s'
                    """.formatted(freshGuestId))).isEqualTo(1);
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.discovery_runs"))
                    .isZero();
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.discovery_proposals"))
                    .isZero();
            assertThat(countRows(statement, """
                    SELECT COUNT(*) FROM onmaru.catalog_dataset_revisions WHERE id = '%s'
                    """.formatted(activeRevisionId))).isEqualTo(1);
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.journey_saved_resources"))
                    .isZero();
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.journey_saved_journeys"))
                    .isZero();
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.stamp_check_ins"))
                    .isZero();
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.stamp_awards"))
                    .isZero();
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.stamp_ranking_profiles"))
                    .isZero();
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.web_idempotency_receipts"))
                    .isZero();
            assertThat(countRows(statement, """
                    SELECT COUNT(*) FROM onmaru.identity_members
                    WHERE id = '%s' AND status = 'DELETING'
                    """.formatted(memberId))).isOne();
            assertThat(countRows(statement, """
                    SELECT COUNT(*) FROM onmaru.identity_deletion_ledger WHERE status = 'COMPLETED'
                    """)).isEqualTo(1);
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.operations_retention_deletion_ledger"))
                    .isEqualTo(12);
        }
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

    private static DataSource dataSource() {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return DriverManager.getConnection(jdbcUrl(), username, password);
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLFeatureNotSupportedException();
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    private static int countRows(java.sql.Statement statement, String sql) throws Exception {
        try (var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(),
                postgres.getMappedPort(POSTGRES_PORT),
                DATABASE);
    }
}

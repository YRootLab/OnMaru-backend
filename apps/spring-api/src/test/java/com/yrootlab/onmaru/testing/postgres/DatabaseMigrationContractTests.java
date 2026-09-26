package com.yrootlab.onmaru.testing.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMigrationContractTests {

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

    @BeforeAll
    static void startPostgres() {
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @Test
    void migratesEmptyDatabaseToLatestBaseline() throws Exception {
        resetAndMigrate();

        try (var connection = connect();
             var statement = connection.createStatement()) {
            assertThat(singleText(statement, """
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """)).isEqualTo("026");
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'onmaru'
                      AND table_name IN (
                        'catalog_dataset_revisions',
                        'identity_members',
                        'operations_sync_runs',
                        'discovery_runs',
                        'community_visit_reviews',
                        'audio_odii_spots',
                        'operations_retention_deletion_ledger'
                      )
                    """)).isEqualTo(7);
        }
    }

    @Test
    void upgradesPreviousBaselineToLatestWithoutLosingRows() throws Exception {
        resetAndMigrateTo("7");
        var memberId = UUID.randomUUID();
        var placeId = UUID.randomUUID();
        var reviewId = UUID.randomUUID();

        try (var connection = connect();
             var statement = connection.createStatement()) {
            insertMember(statement, memberId);
            insertPlace(statement, placeId);
            insertReview(statement, reviewId, memberId, placeId, "직전 baseline 보존", "PUBLISHED");
        }

        migrate();

        try (var connection = connect();
             var statement = connection.createStatement()) {
            assertThat(singleText(statement, """
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """)).isEqualTo("026");
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM onmaru.community_visit_reviews
                    WHERE id = '%s'
                    """.formatted(reviewId))).isEqualTo(1);
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'onmaru'
                      AND table_name IN ('audio_odii_spots', 'audio_story_versions')
                    """)).isEqualTo(2);
        }
    }

    @Test
    void keepsMigrationsForwardOnlyWithoutAutomaticDowngradeScripts() throws Exception {
        var migrationDirectory = Path.of("src/main/resources/db/migration/baseline");

        try (var files = Files.list(migrationDirectory)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .allMatch(fileName -> fileName.startsWith("V") && fileName.endsWith(".sql"));
        }
    }

    @Test
    void provesRequiredDatabaseConcurrencyMatrix() throws Exception {
        resetAndMigrate();

        try (var connection = connect();
             var statement = connection.createStatement()) {
            assertOwnerXorRejectsBothInvalidShapes(statement);
            assertConcurrentExternalIdentityLinkCreatesNoOrphanMember(statement);
            assertGuestGrantClaimRaceAllowsOneMember(statement);
            assertOperationAdmissionRaceAllowsNoOverLimit(statement);
            assertRunAdmissionRaceAllowsOneActiveRun(statement);
            assertActorAdmissionRaceAllowsOneActiveActorRun(statement);
            assertCancelAndCompletionRaceLeavesOneTerminalState(statement);
            assertDeadlineSweeperBlocksExpiredCompletion(statement);
            assertDuplicateTurnCommandCreatesOneBusinessEffect(statement);
            assertMemberDeletionStartedBeforeLateCompletionDiscardsResult(statement);
            assertConcurrentSavesAndLikesCreateOneRow(statement);
            assertDuplicateReportSubmissionsCreateOneOpenReport(statement);
            assertPublishCasActivatesOneRevision(statement);
            assertPublicReviewQueryNeverReturnsModeratedText(statement);
        }
    }

    private static void assertOwnerXorRejectsBothInvalidShapes(Statement statement) {
        var memberId = UUID.randomUUID();
        var guestId = UUID.randomUUID();

        assertThatThrownBy(() -> statement.execute("""
                INSERT INTO onmaru.discovery_explorations (
                    id, state_version, pinned_refs, excluded_refs, created_at, updated_at
                ) VALUES (
                    gen_random_uuid(), 0, '[]'::jsonb, '[]'::jsonb,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("discovery_explorations_owner_xor_ck");

        assertThatThrownBy(() -> {
            insertMember(statement, memberId);
            insertGuest(statement, guestId, "owner-xor-token");
            statement.execute("""
                    INSERT INTO onmaru.discovery_explorations (
                        id, owner_member_id, owner_guest_id, state_version,
                        pinned_refs, excluded_refs, created_at, updated_at
                    ) VALUES (
                        gen_random_uuid(), '%s', '%s', 0,
                        '[]'::jsonb, '[]'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    """.formatted(memberId, guestId));
        })
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("discovery_explorations_owner_xor_ck");
    }

    private static void assertOperationAdmissionRaceAllowsNoOverLimit(Statement statement) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.operations_admission (
                    scope_key, window_start, consumed, active_count
                ) VALUES (
                    'review.write:member:member-1',
                    '2026-09-15T03:00:00+09:00',
                    0,
                    0
                )
                """);

        var results = runRace(
                () -> consumeAdmission("review.write:member:member-1", 1),
                () -> consumeAdmission("review.write:member:member-1", 1)
        );

        assertThat(results).containsExactlyInAnyOrder(RaceResult.UPDATED, RaceResult.NOOP);
        assertThat(countRows(statement, """
                SELECT consumed
                FROM onmaru.operations_admission
                WHERE scope_key = 'review.write:member:member-1'
                """)).isEqualTo(1);
    }

    private static void assertConcurrentExternalIdentityLinkCreatesNoOrphanMember(Statement statement)
            throws Exception {
        var results = runRace(
                () -> insertMemberAndExternalAccount(UUID.randomUUID(), "KAKAO", "https://kauth.kakao.com", "race-subject"),
                () -> insertMemberAndExternalAccount(UUID.randomUUID(), "KAKAO", "https://kauth.kakao.com", "race-subject")
        );

        assertThat(results).containsExactlyInAnyOrder(RaceResult.SUCCESS, RaceResult.CONFLICT);
        assertThat(countRows(statement, """
                SELECT COUNT(*)
                FROM onmaru.identity_external_accounts
                WHERE provider = 'KAKAO'
                  AND issuer = 'https://kauth.kakao.com'
                  AND subject = 'race-subject'
                """)).isEqualTo(1);
        assertThat(countRows(statement, """
                SELECT COUNT(*)
                FROM onmaru.identity_members member
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM onmaru.identity_external_accounts account
                    WHERE account.member_id = member.id
                )
                  AND member.created_at = '2026-09-15T00:00:00Z'
                """)).isZero();
    }

    private static void assertGuestGrantClaimRaceAllowsOneMember(Statement statement) throws Exception {
        var firstMember = UUID.randomUUID();
        var secondMember = UUID.randomUUID();
        var guestId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        insertMember(statement, firstMember);
        insertMember(statement, secondMember);
        insertGuest(statement, guestId, "grant-race-token");
        insertGuestExploration(statement, explorationId, guestId);

        var results = runRace(
                () -> insertExplorationGrant(firstMember, explorationId),
                () -> insertExplorationGrant(secondMember, explorationId)
        );

        assertThat(results).containsExactlyInAnyOrder(RaceResult.SUCCESS, RaceResult.CONFLICT);
        assertThat(countRows(statement, """
                SELECT COUNT(*)
                FROM onmaru.identity_exploration_grants
                WHERE exploration_id = '%s'
                """.formatted(explorationId))).isEqualTo(1);
    }

    private static void assertRunAdmissionRaceAllowsOneActiveRun(Statement statement) throws Exception {
        var memberId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        insertMember(statement, memberId);
        insertExploration(statement, explorationId, memberId);

        var results = runRace(
                () -> insertRun(UUID.randomUUID(), explorationId, "actor:run-a", "RUNNING", null),
                () -> insertRun(UUID.randomUUID(), explorationId, "actor:run-b", "RUNNING", null)
        );

        assertThat(results).containsExactlyInAnyOrder(RaceResult.SUCCESS, RaceResult.CONFLICT);
        assertThat(countRows(statement, """
                SELECT COUNT(*)
                FROM onmaru.discovery_runs
                WHERE exploration_id = '%s'
                  AND status IN ('QUEUED', 'RUNNING')
                """.formatted(explorationId))).isEqualTo(1);
    }

    private static void assertActorAdmissionRaceAllowsOneActiveActorRun(Statement statement) throws Exception {
        var memberId = UUID.randomUUID();
        var firstExplorationId = UUID.randomUUID();
        var secondExplorationId = UUID.randomUUID();
        var actorKey = "actor:member:%s".formatted(memberId);
        insertMember(statement, memberId);
        insertExploration(statement, firstExplorationId, memberId);
        insertExploration(statement, secondExplorationId, memberId);

        var results = runRace(
                () -> insertRun(UUID.randomUUID(), firstExplorationId, actorKey, "RUNNING", null),
                () -> insertRun(UUID.randomUUID(), secondExplorationId, actorKey, "RUNNING", null)
        );

        assertThat(results).containsExactlyInAnyOrder(RaceResult.SUCCESS, RaceResult.CONFLICT);
        assertThat(countRows(statement, """
                SELECT COUNT(*)
                FROM onmaru.discovery_runs
                WHERE actor_key = '%s'
                  AND status IN ('QUEUED', 'RUNNING')
                """.formatted(actorKey))).isEqualTo(1);
    }

    private static void assertCancelAndCompletionRaceLeavesOneTerminalState(Statement statement) throws Exception {
        var memberId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        insertMember(statement, memberId);
        insertExploration(statement, explorationId, memberId);
        insertRun(statement, runId, explorationId, "actor:cancel-complete", "RUNNING", null);

        var results = runRace(
                () -> updateRows("""
                        UPDATE onmaru.discovery_runs
                        SET status = 'CANCELLED',
                            error_code = 'USER_CANCELLED'
                        WHERE id = '%s'
                          AND status = 'RUNNING'
                        """.formatted(runId)),
                () -> updateRows("""
                        UPDATE onmaru.discovery_runs
                        SET status = 'COMPLETED',
                            outcome = 'BOARD_READY'
                        WHERE id = '%s'
                          AND status = 'RUNNING'
                        """.formatted(runId))
        );

        assertThat(results).containsExactlyInAnyOrder(RaceResult.UPDATED, RaceResult.NOOP);
        assertThat(countRows(statement, """
                SELECT COUNT(*)
                FROM onmaru.discovery_runs
                WHERE id = '%s'
                  AND status IN ('COMPLETED', 'CANCELLED')
                """.formatted(runId))).isEqualTo(1);
    }

    private static void assertDeadlineSweeperBlocksExpiredCompletion(Statement statement) throws Exception {
        var memberId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        insertMember(statement, memberId);
        insertExploration(statement, explorationId, memberId);
        statement.execute("""
                INSERT INTO onmaru.discovery_runs (
                    id, exploration_id, actor_key, base_version, status, outcome,
                    created_at, deadline_at, generation, engine
                ) VALUES (
                    '%s', '%s', 'actor:expired-worker', 0, 'RUNNING', NULL,
                    CURRENT_TIMESTAMP - interval '2 minutes',
                    CURRENT_TIMESTAMP - interval '1 minute',
                    1, 'BASELINE'
                )
                """.formatted(runId, explorationId));

        var results = runRace(
                () -> updateRows("""
                        UPDATE onmaru.discovery_runs
                        SET status = 'FAILED',
                            error_code = 'DEADLINE_EXPIRED'
                        WHERE id = '%s'
                          AND status = 'RUNNING'
                          AND deadline_at < CURRENT_TIMESTAMP
                        """.formatted(runId)),
                () -> updateRows("""
                        UPDATE onmaru.discovery_runs
                        SET status = 'COMPLETED',
                            outcome = 'BOARD_READY'
                        WHERE id = '%s'
                          AND status = 'RUNNING'
                          AND deadline_at >= CURRENT_TIMESTAMP
                        """.formatted(runId))
        );

        assertThat(results).containsExactlyInAnyOrder(RaceResult.UPDATED, RaceResult.NOOP);
        assertThat(singleText(statement, """
                SELECT status::text
                FROM onmaru.discovery_runs
                WHERE id = '%s'
                """.formatted(runId))).isEqualTo("FAILED");
    }

    private static void assertDuplicateTurnCommandCreatesOneBusinessEffect(Statement statement) throws Exception {
        var memberId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        var clientTurnId = UUID.randomUUID();
        insertMember(statement, memberId);
        insertExploration(statement, explorationId, memberId);

        var results = runRace(
                () -> insertTurn(UUID.randomUUID(), explorationId, clientTurnId, "아이와 갈 실내 코스"),
                () -> insertTurn(UUID.randomUUID(), explorationId, clientTurnId, "아이와 갈 실내 코스")
        );

        assertThat(results).containsExactlyInAnyOrder(RaceResult.SUCCESS, RaceResult.CONFLICT);
        assertThat(countRows(statement, """
                SELECT COUNT(*)
                FROM onmaru.discovery_turns
                WHERE exploration_id = '%s'
                  AND client_turn_id = '%s'
                """.formatted(explorationId, clientTurnId))).isEqualTo(1);
    }

    private static void assertMemberDeletionStartedBeforeLateCompletionDiscardsResult(Statement statement)
            throws Exception {
        var memberId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        insertMember(statement, memberId);
        insertExploration(statement, explorationId, memberId);
        insertRun(statement, runId, explorationId, "actor:late-result", "RUNNING", null);

        statement.execute("""
                UPDATE onmaru.identity_members
                SET status = 'DELETING'
                WHERE id = '%s'
                """.formatted(memberId));
        statement.execute("""
                INSERT INTO onmaru.identity_deletion_ledger (
                    member_id, requested_at, status, reason
                ) VALUES (
                    '%s', CURRENT_TIMESTAMP, 'REQUESTED', 'member-request'
                )
                """.formatted(memberId));

        assertThat(statement.executeUpdate("""
                UPDATE onmaru.discovery_explorations exploration
                SET board = '{"cards":[]}'::jsonb,
                    state_version = state_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE exploration.id = '%s'
                  AND EXISTS (
                    SELECT 1
                    FROM onmaru.identity_members member
                    WHERE member.id = exploration.owner_member_id
                      AND member.status = 'ACTIVE'
                  )
                  AND NOT EXISTS (
                    SELECT 1
                    FROM onmaru.identity_deletion_ledger ledger
                    WHERE ledger.member_id = exploration.owner_member_id
                      AND ledger.status IN ('REQUESTED', 'COMPLETED')
                  )
                """.formatted(explorationId))).isZero();
        assertThat(statement.executeUpdate("""
                UPDATE onmaru.discovery_runs
                SET status = 'COMPLETED',
                    outcome = 'BOARD_READY'
                WHERE id = '%s'
                  AND status = 'RUNNING'
                  AND EXISTS (
                    SELECT 1
                    FROM onmaru.identity_members member
                    WHERE member.id = '%s'
                      AND member.status = 'ACTIVE'
                  )
                """.formatted(runId, memberId))).isZero();
        assertThat(countRows(statement, """
                SELECT COUNT(*)
                FROM onmaru.discovery_explorations
                WHERE id = '%s'
                  AND board IS NOT NULL
                """.formatted(explorationId))).isZero();
    }

    private static void assertConcurrentSavesAndLikesCreateOneRow(Statement statement) throws Exception {
        var authorId = UUID.randomUUID();
        var memberId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        var placeId = UUID.randomUUID();
        var reviewId = UUID.randomUUID();
        insertMember(statement, authorId);
        insertMember(statement, memberId);
        insertExploration(statement, explorationId, memberId);
        insertPlace(statement, placeId);
        insertReview(statement, reviewId, authorId, placeId, "좋아요 경합 테스트", "PUBLISHED");

        var saveResults = runRace(
                () -> insertSavedResource(UUID.randomUUID(), memberId, "PLACE", placeId),
                () -> insertSavedResource(UUID.randomUUID(), memberId, "PLACE", placeId)
        );
        var likeResults = runRace(
                () -> insertLike(reviewId, memberId),
                () -> insertLike(reviewId, memberId)
        );

        assertThat(saveResults).containsExactlyInAnyOrder(RaceResult.SUCCESS, RaceResult.CONFLICT);
        assertThat(likeResults).containsExactlyInAnyOrder(RaceResult.SUCCESS, RaceResult.CONFLICT);
        assertThat(countRows(statement, """
                SELECT COUNT(*)
                FROM onmaru.journey_saved_resources
                WHERE member_id = '%s'
                  AND resource_type = 'PLACE'
                  AND resource_id = '%s'
                """.formatted(memberId, placeId))).isEqualTo(1);
        assertThat(countRows(statement, """
                SELECT COUNT(*)
                FROM onmaru.community_review_likes
                WHERE review_id = '%s'
                  AND member_id = '%s'
                """.formatted(reviewId, memberId))).isEqualTo(1);
    }

    private static void assertDuplicateReportSubmissionsCreateOneOpenReport(Statement statement) throws Exception {
        var authorId = UUID.randomUUID();
        var reporterId = UUID.randomUUID();
        var placeId = UUID.randomUUID();
        var reviewId = UUID.randomUUID();
        insertMember(statement, authorId);
        insertMember(statement, reporterId);
        insertPlace(statement, placeId);
        insertReview(statement, reviewId, authorId, placeId, "신고 경합 테스트", "PUBLISHED");

        var results = runRace(
                () -> insertReport(UUID.randomUUID(), reviewId, reporterId),
                () -> insertReport(UUID.randomUUID(), reviewId, reporterId)
        );

        assertThat(results).containsExactlyInAnyOrder(RaceResult.SUCCESS, RaceResult.CONFLICT);
        assertThat(countRows(statement, """
                SELECT COUNT(*)
                FROM onmaru.community_review_reports
                WHERE review_id = '%s'
                  AND reporter_member_id = '%s'
                  AND status = 'OPEN'
                """.formatted(reviewId, reporterId))).isEqualTo(1);
    }

    private static void assertPublishCasActivatesOneRevision(Statement statement) throws Exception {
        var baseRevisionId = UUID.randomUUID();
        var firstRevisionId = UUID.randomUUID();
        var secondRevisionId = UUID.randomUUID();
        insertRevision(statement, baseRevisionId, "publish-cas", "PUBLISHED");
        insertRevision(statement, firstRevisionId, "publish-cas", "READY");
        insertRevision(statement, secondRevisionId, "publish-cas", "READY");
        statement.execute("""
                INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                VALUES ('publish-cas', '%s', CURRENT_TIMESTAMP)
                """.formatted(baseRevisionId));

        var results = runRace(
                () -> activateRevision("publish-cas", baseRevisionId, firstRevisionId),
                () -> activateRevision("publish-cas", baseRevisionId, secondRevisionId)
        );

        assertThat(results).containsExactlyInAnyOrder(RaceResult.UPDATED, RaceResult.NOOP);
        assertThat(countRows(statement, """
                SELECT COUNT(*)
                FROM onmaru.catalog_active_datasets
                WHERE dataset = 'publish-cas'
                  AND revision_id IN ('%s', '%s')
                """.formatted(firstRevisionId, secondRevisionId))).isEqualTo(1);
    }

    private static void assertPublicReviewQueryNeverReturnsModeratedText(Statement statement) throws Exception {
        var authorId = UUID.randomUUID();
        var placeId = UUID.randomUUID();
        var publishedReviewId = UUID.randomUUID();
        var hiddenReviewId = UUID.randomUUID();
        var removedReviewId = UUID.randomUUID();
        insertMember(statement, authorId);
        insertPlace(statement, placeId);
        insertReview(statement, publishedReviewId, authorId, placeId, "공개 후기", "PUBLISHED");
        insertReview(statement, hiddenReviewId, authorId, placeId, "숨김 후기", "HIDDEN");
        insertReview(statement, removedReviewId, authorId, placeId, "삭제 후기", "REMOVED");

        assertThat(singleText(statement, """
                SELECT string_agg(text, ',' ORDER BY text)
                FROM onmaru.community_visit_reviews
                WHERE place_id = '%s'
                  AND status = 'PUBLISHED'
                """.formatted(placeId))).isEqualTo("공개 후기");

        statement.execute("""
                UPDATE onmaru.community_visit_reviews
                SET status = 'HIDDEN'
                WHERE id = '%s'
                """.formatted(publishedReviewId));

        assertThat(countRows(statement, """
                SELECT COUNT(*)
                FROM onmaru.community_visit_reviews
                WHERE place_id = '%s'
                  AND status = 'PUBLISHED'
                """.formatted(placeId))).isZero();
    }

    private static List<RaceResult> runRace(Callable<RaceResult> first, Callable<RaceResult> second)
            throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> awaitAndCall(barrier, first));
            var secondFuture = executor.submit(() -> awaitAndCall(barrier, second));
            executor.shutdown();
            assertThat(executor.awaitTermination(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            return List.of(firstFuture.get(), secondFuture.get());
        }
    }

    private static RaceResult awaitAndCall(CyclicBarrier barrier, Callable<RaceResult> operation) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return operation.call();
    }

    private static RaceResult insertRun(
            UUID runId,
            UUID explorationId,
            String actorKey,
            String status,
            String outcome
    ) throws Exception {
        return executeInsert("""
                INSERT INTO onmaru.discovery_runs (
                    id, exploration_id, actor_key, base_version, status, outcome,
                    created_at, deadline_at, generation, engine
                ) VALUES (
                    '%s', '%s', '%s', 0, '%s', %s,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + interval '20 seconds', 1, 'BASELINE'
                )
                """.formatted(runId, explorationId, actorKey, status, sqlNullable(outcome)));
    }

    private static RaceResult insertMemberAndExternalAccount(
            UUID memberId,
            String provider,
            String issuer,
            String subject
    ) throws Exception {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO onmaru.identity_members (id, status, created_at)
                        VALUES ('%s', 'ACTIVE', '2026-09-15T00:00:00Z')
                        """.formatted(memberId));
                statement.execute("""
                        INSERT INTO onmaru.identity_external_accounts (
                            id, member_id, provider, issuer, subject, created_at
                        ) VALUES (
                            gen_random_uuid(), '%s', '%s', '%s', '%s', CURRENT_TIMESTAMP
                        )
                        """.formatted(memberId, provider, issuer, subject));
                connection.commit();
                return RaceResult.SUCCESS;
            } catch (SQLException exception) {
                connection.rollback();
                if (isUniqueViolation(exception)) {
                    return RaceResult.CONFLICT;
                }
                throw exception;
            }
        }
    }

    private static void insertRun(
            Statement statement,
            UUID runId,
            UUID explorationId,
            String actorKey,
            String status,
            String outcome
    ) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.discovery_runs (
                    id, exploration_id, actor_key, base_version, status, outcome,
                    created_at, deadline_at, generation, engine
                ) VALUES (
                    '%s', '%s', '%s', 0, '%s', %s,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + interval '20 seconds', 1, 'BASELINE'
                )
                """.formatted(runId, explorationId, actorKey, status, sqlNullable(outcome)));
    }

    private static RaceResult insertTurn(UUID turnId, UUID explorationId, UUID clientTurnId, String query)
            throws Exception {
        return executeInsert("""
                INSERT INTO onmaru.discovery_turns (
                    id, exploration_id, client_turn_id, query, created_at
                ) VALUES (
                    '%s', '%s', '%s', '%s', CURRENT_TIMESTAMP
                )
                """.formatted(turnId, explorationId, clientTurnId, query));
    }

    private static RaceResult insertSavedResource(
            UUID savedResourceId,
            UUID memberId,
            String resourceType,
            UUID resourceId
    ) throws Exception {
        return executeInsert("""
                INSERT INTO onmaru.journey_saved_resources (
                    id, member_id, resource_type, resource_id, saved_at
                ) VALUES (
                    '%s', '%s', '%s', '%s', CURRENT_TIMESTAMP
                )
                """.formatted(savedResourceId, memberId, resourceType, resourceId));
    }

    private static RaceResult insertExplorationGrant(UUID memberId, UUID explorationId) throws Exception {
        return executeInsert("""
                INSERT INTO onmaru.identity_exploration_grants (
                    member_id, exploration_id, expires_at
                ) VALUES (
                    '%s', '%s', CURRENT_TIMESTAMP + interval '10 minutes'
                )
                """.formatted(memberId, explorationId));
    }

    private static RaceResult insertLike(UUID reviewId, UUID memberId) throws Exception {
        return executeInsert("""
                INSERT INTO onmaru.community_review_likes (review_id, member_id, created_at)
                VALUES ('%s', '%s', CURRENT_TIMESTAMP)
                """.formatted(reviewId, memberId));
    }

    private static RaceResult insertReport(UUID reportId, UUID reviewId, UUID reporterId) throws Exception {
        return executeInsert("""
                INSERT INTO onmaru.community_review_reports (
                    id, review_id, reporter_member_id, reason, detail, status, created_at
                ) VALUES (
                    '%s', '%s', '%s', 'SPAM', '중복 신고', 'OPEN', CURRENT_TIMESTAMP
                )
                """.formatted(reportId, reviewId, reporterId));
    }

    private static RaceResult activateRevision(String dataset, UUID expectedRevisionId, UUID nextRevisionId)
            throws Exception {
        return updateRows("""
                UPDATE onmaru.catalog_active_datasets
                SET revision_id = '%s',
                    activated_at = CURRENT_TIMESTAMP
                WHERE dataset = '%s'
                  AND revision_id = '%s'
                """.formatted(nextRevisionId, dataset, expectedRevisionId));
    }

    private static RaceResult consumeAdmission(String scopeKey, int limit) throws Exception {
        return updateRows("""
                UPDATE onmaru.operations_admission
                SET consumed = consumed + 1
                WHERE scope_key = '%s'
                  AND window_start = '2026-09-15T03:00:00+09:00'
                  AND consumed < %d
                """.formatted(scopeKey, limit));
    }

    private static RaceResult executeInsert(String sql) throws Exception {
        try (var connection = connect();
             var statement = connection.createStatement()) {
            statement.execute(sql);
            return RaceResult.SUCCESS;
        } catch (SQLException exception) {
            if (isUniqueViolation(exception)) {
                return RaceResult.CONFLICT;
            }
            throw exception;
        }
    }

    private static boolean isUniqueViolation(SQLException exception) {
        return "23505".equals(exception.getSQLState());
    }

    private static RaceResult updateRows(String sql) throws Exception {
        try (var connection = connect();
             var statement = connection.createStatement()) {
            return statement.executeUpdate(sql) == 1 ? RaceResult.UPDATED : RaceResult.NOOP;
        }
    }

    private static void insertMember(Statement statement, UUID memberId) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.identity_members (id, status, created_at)
                VALUES ('%s', 'ACTIVE', CURRENT_TIMESTAMP)
                """.formatted(memberId));
    }

    private static void insertGuest(Statement statement, UUID guestId, String tokenSeed) throws Exception {
        var tokenHash = (Integer.toHexString(tokenSeed.hashCode()).replace("-", "") + "0".repeat(64))
                .substring(0, 64);
        statement.execute("""
                INSERT INTO onmaru.identity_guests (id, token_hash, expires_at)
                VALUES ('%s', '%s', CURRENT_TIMESTAMP + interval '1 hour')
                """.formatted(guestId, tokenHash));
    }

    private static void insertExploration(Statement statement, UUID explorationId, UUID memberId) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.discovery_explorations (
                    id, owner_member_id, state_version, pinned_refs, excluded_refs,
                    created_at, updated_at
                ) VALUES (
                    '%s', '%s', 0, '[]'::jsonb, '[]'::jsonb,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """.formatted(explorationId, memberId));
    }

    private static void insertGuestExploration(Statement statement, UUID explorationId, UUID guestId)
            throws Exception {
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

    private static void insertPlace(Statement statement, UUID placeId) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.catalog_place_identity (id, created_at)
                VALUES ('%s', CURRENT_TIMESTAMP)
                """.formatted(placeId));
    }

    private static void insertReview(
            Statement statement,
            UUID reviewId,
            UUID memberId,
            UUID placeId,
            String text,
            String status
    ) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.community_visit_reviews (
                    id, member_id, place_id, text, status, created_at
                ) VALUES (
                    '%s', '%s', '%s', '%s', '%s', CURRENT_TIMESTAMP
                )
                """.formatted(reviewId, memberId, placeId, text.replace("'", "''"), status));
    }

    private static void insertRevision(Statement statement, UUID revisionId, String dataset, String status)
            throws Exception {
        var publishedAt = "PUBLISHED".equals(status) ? "CURRENT_TIMESTAMP" : "NULL";
        statement.execute("""
                INSERT INTO onmaru.catalog_dataset_revisions (
                    id, dataset, status, source_observed_at, fetched_at, published_at
                ) VALUES (
                    '%s', '%s', '%s', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, %s
                )
                """.formatted(revisionId, dataset, status, publishedAt));
    }

    private static void resetAndMigrate() throws Exception {
        reset();
        migrate();
    }

    private static void resetAndMigrateTo(String target) throws Exception {
        reset();
        Flyway.configure()
                .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                .locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target(target)
                .load()
                .migrate();
    }

    private static void reset() throws Exception {
        try (var connection = connect()) {
            PostgresTestDatabase.reset(connection);
        }
    }

    private static void migrate() {
        Flyway.configure()
                .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                .locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private static int countRows(Statement statement, String sql) throws Exception {
        try (var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static String singleText(Statement statement, String sql) throws Exception {
        try (var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(),
                postgres.getMappedPort(POSTGRES_PORT),
                DATABASE);
    }

    private static String sqlNullable(String value) {
        return value == null ? "NULL" : "'%s'".formatted(value);
    }

    private enum RaceResult {
        SUCCESS,
        CONFLICT,
        UPDATED,
        NOOP
    }
}

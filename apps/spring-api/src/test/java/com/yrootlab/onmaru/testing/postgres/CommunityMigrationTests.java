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
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommunityMigrationTests {

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
    void migratesVisitReviewLikeReportAndModerationSchema() throws Exception {
        resetAndMigrate();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'onmaru'
                      AND table_name IN (
                        'community_visit_reviews',
                        'community_review_likes',
                        'community_review_reports',
                        'community_review_moderation_actions'
                      )
                    """)).isEqualTo(4);
        }
    }

    @Test
    void preventsDuplicateLikesAndReportsForSameMember() throws Exception {
        resetAndMigrate();
        var authorId = UUID.randomUUID();
        var reporterId = UUID.randomUUID();
        var placeId = UUID.randomUUID();
        var reviewId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertMember(statement, authorId);
            insertMember(statement, reporterId);
            insertPlace(statement, placeId);
            insertReview(statement, reviewId, authorId, placeId, "전주 한옥마을 산책이 좋았어요.", "PUBLISHED");
            insertLike(statement, reviewId, reporterId);
            insertReport(statement, UUID.randomUUID(), reviewId, reporterId, "SPAM", "반복 홍보");

            assertThatThrownBy(() -> insertLike(statement, reviewId, reporterId))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("community_review_likes_pkey");

            assertThatThrownBy(() -> insertReport(
                    statement,
                    UUID.randomUUID(),
                    reviewId,
                    reporterId,
                    "SPAM",
                    "다시 신고"
            ))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("community_review_reports_open_uq");
        }
    }

    @Test
    void rejectsInvalidReviewReportAndSelfReport() throws Exception {
        resetAndMigrate();
        var authorId = UUID.randomUUID();
        var placeId = UUID.randomUUID();
        var reviewId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertMember(statement, authorId);
            insertPlace(statement, placeId);

            assertThatThrownBy(() -> insertReview(statement, UUID.randomUUID(), authorId, placeId, "   ", "PUBLISHED"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("community_visit_reviews_text_ck");

            insertReview(statement, reviewId, authorId, placeId, "짧은 방문 후기", "PUBLISHED");

            assertThatThrownBy(() -> insertReport(
                    statement,
                    UUID.randomUUID(),
                    reviewId,
                    authorId,
                    "ABUSE",
                    "본인 신고"
            ))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("community_review_reports_not_self_ck");

            assertThatThrownBy(() -> insertReport(
                    statement,
                    UUID.randomUUID(),
                    reviewId,
                    UUID.randomUUID(),
                    "NOT_ALLOWED",
                    "허용되지 않은 사유"
            ))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("community_review_reports_reason_ck");
        }
    }

    @Test
    void storesModerationAuditAsAppendOnlyRows() throws Exception {
        resetAndMigrate();
        var authorId = UUID.randomUUID();
        var placeId = UUID.randomUUID();
        var reviewId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertMember(statement, authorId);
            insertPlace(statement, placeId);
            insertReview(statement, reviewId, authorId, placeId, "운영 판정 테스트", "PUBLISHED");
            insertModerationAction(statement, UUID.randomUUID(), reviewId, "PUBLISHED", "HIDDEN", "PII_HIGH_RISK");
            insertModerationAction(statement, UUID.randomUUID(), reviewId, "HIDDEN", "REMOVED", "COPYRIGHT_CONFIRMED");

            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM onmaru.community_review_moderation_actions
                    WHERE review_id = '%s'
                    """.formatted(reviewId))).isEqualTo(2);

            assertThatThrownBy(() -> insertModerationAction(
                    statement,
                    UUID.randomUUID(),
                    reviewId,
                    "PUBLISHED",
                    "PUBLISHED",
                    "NO_CHANGE"
            ))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("community_review_moderation_actions_status_change_ck");
        }
    }

    private static void insertMember(Statement statement, UUID memberId) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.identity_members (id, status, created_at)
                VALUES ('%s', 'ACTIVE', CURRENT_TIMESTAMP)
                """.formatted(memberId));
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

    private static void insertLike(Statement statement, UUID reviewId, UUID memberId) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.community_review_likes (review_id, member_id, created_at)
                VALUES ('%s', '%s', CURRENT_TIMESTAMP)
                """.formatted(reviewId, memberId));
    }

    private static void insertReport(
            Statement statement,
            UUID reportId,
            UUID reviewId,
            UUID reporterMemberId,
            String reason,
            String detail
    ) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.community_review_reports (
                    id, review_id, reporter_member_id, reason, detail, status, created_at
                ) VALUES (
                    '%s', '%s', '%s', '%s', '%s', 'OPEN', CURRENT_TIMESTAMP
                )
                """.formatted(reportId, reviewId, reporterMemberId, reason, detail.replace("'", "''")));
    }

    private static void insertModerationAction(
            Statement statement,
            UUID actionId,
            UUID reviewId,
            String previousStatus,
            String nextStatus,
            String reason
    ) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.community_review_moderation_actions (
                    id, review_id, actor_type, actor_ref, previous_status, next_status, reason, created_at
                ) VALUES (
                    '%s', '%s', 'OPERATOR', 'operator-1', '%s', '%s', '%s', CURRENT_TIMESTAMP
                )
                """.formatted(actionId, reviewId, previousStatus, nextStatus, reason));
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

    private static int countRows(Statement statement, String sql) throws Exception {
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

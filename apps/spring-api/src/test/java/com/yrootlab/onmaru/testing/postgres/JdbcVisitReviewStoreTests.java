package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.persistence.catalog.JdbcCatalogPublicPlaceIdStore;
import com.yrootlab.onmaru.persistence.community.JdbcVisitReviewStore;
import com.yrootlab.onmaru.persistence.community.JdbcReviewReportStore;
import com.yrootlab.onmaru.persistence.community.JdbcModerationQueueReadStore;
import com.yrootlab.onmaru.community.moderation.ReviewReport;
import com.yrootlab.onmaru.community.moderation.ReviewReportReason;
import com.yrootlab.onmaru.community.moderation.ReviewReportStatus;
import com.yrootlab.onmaru.community.moderation.ModerationQueuePriority;
import com.yrootlab.onmaru.persistence.admin.JdbcAdminMemberStore;
import com.yrootlab.onmaru.persistence.admin.JdbcAdminCurationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcVisitReviewStoreTests {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private DataSource dataSource;

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
    }

    @Test
    void preservesThePublicPlaceSnapshotAndLikesAcrossStoreInstances() throws Exception {
        var catalogPlaceId = UUID.randomUUID();
        var authorId = UUID.randomUUID();
        var likerId = UUID.randomUUID();
        seedPlaceIdentity(catalogPlaceId);
        seedMember(authorId);
        seedMember(likerId);

        var publicPlaceIds = new JdbcCatalogPublicPlaceIdStore(dataSource);
        publicPlaceIds.register("p-jeonju-hanok-village", catalogPlaceId);
        var review = new VisitReviewProjection(
                UUID.randomUUID(),
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                "kr-45-jeonju",
                35.8151,
                127.1530,
                "처마 아래에서 쉬기 좋았습니다.",
                "한적",
                5,
                List.of("고즈넉함", "처마"),
                Instant.parse("2026-09-24T00:00:00Z"),
                authorId,
                Set.of(likerId),
                VisitReviewStatus.PUBLISHED);

        new JdbcVisitReviewStore(dataSource, publicPlaceIds).add(review);

        assertThat(new JdbcVisitReviewStore(dataSource, publicPlaceIds).findSnapshot())
                .containsExactly(review);
    }

    @Test
    void adminPagesKeepSameTimestampRowsWithoutDuplicatesAndApplyStatusFilter() throws Exception {
        var placeId = UUID.randomUUID();
        var authorId = UUID.randomUUID();
        seedPlaceIdentity(placeId);
        seedMember(authorId);
        var publicPlaceIds = new JdbcCatalogPublicPlaceIdStore(dataSource);
        publicPlaceIds.register("p-admin-page", placeId);
        var store = new JdbcVisitReviewStore(dataSource, publicPlaceIds);
        var at = Instant.parse("2026-09-24T00:00:00Z");
        for (int suffix = 1; suffix <= 4; suffix++) {
            var review = new VisitReviewProjection(new UUID(0, suffix), "p-admin-page", "장소", "kr-45-jeonju",
                    35.8151, 127.1530, "후기", null, null, List.of(), at, authorId, Set.of(),
                    suffix == 4 ? VisitReviewStatus.HIDDEN : VisitReviewStatus.PUBLISHED);
            store.add(review);
        }

        var first = store.findAdminPage(VisitReviewStatus.PUBLISHED, 2, null);
        assertThat(first.items()).extracting(VisitReviewProjection::id)
                .containsExactly(new UUID(0, 3), new UUID(0, 2));
        assertThat(first.hasNext()).isTrue();
        var last = first.items().getLast();
        var second = store.findAdminPage(VisitReviewStatus.PUBLISHED, 2,
                new AdminCursor("reviews", 2, "status=PUBLISHED", last.createdAt(), last.id()));
        assertThat(second.items()).extracting(VisitReviewProjection::id).containsExactly(new UUID(0, 1));
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    void adminPageCountsOnlyRowsWithACompletePlaceSnapshot() throws Exception {
        var placeId = UUID.randomUUID();
        var authorId = UUID.randomUUID();
        seedPlaceIdentity(placeId);
        seedMember(authorId);
        var publicPlaceIds = new JdbcCatalogPublicPlaceIdStore(dataSource);
        publicPlaceIds.register("p-admin-page", placeId);
        var store = new JdbcVisitReviewStore(dataSource, publicPlaceIds);
        var at = Instant.parse("2026-09-24T00:00:00Z");
        for (int suffix = 1; suffix <= 3; suffix++) {
            store.add(new VisitReviewProjection(new UUID(0, suffix), "p-admin-page", "장소", "kr-45-jeonju",
                    35.8151, 127.1530, "후기", null, null, List.of(), at, authorId, Set.of(),
                    VisitReviewStatus.PUBLISHED));
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.community_visit_reviews
                         (id,member_id,place_id,public_place_id,place_name,text,status,created_at)
                     VALUES (?,?,?,'p-admin-page','장소','좌표 없는 후기','PUBLISHED',?)
                     """)) {
            statement.setObject(1, new UUID(0, 4));
            statement.setObject(2, authorId);
            statement.setObject(3, placeId);
            statement.setObject(4, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
            statement.executeUpdate();
        }

        var page = store.findAdminPage(VisitReviewStatus.PUBLISHED, 2, null);
        assertThat(page.items()).extracting(VisitReviewProjection::id)
                .containsExactly(new UUID(0, 3), new UUID(0, 2));
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void adminMemberPagesKeepSameTimestampRowsAndStatusFilter() throws Exception {
        var at = OffsetDateTime.of(2026, 9, 24, 0, 0, 0, 0, ZoneOffset.UTC);
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO onmaru.identity_members (id,status,created_at) VALUES (?, ?::onmaru.identity_member_status, ?)")) {
            for (int suffix = 1; suffix <= 4; suffix++) {
                statement.setObject(1, new UUID(0, suffix));
                statement.setString(2, suffix == 4 ? "DELETING" : "ACTIVE");
                statement.setObject(3, at);
                statement.addBatch();
            }
            statement.executeBatch();
        }

        var store = new JdbcAdminMemberStore(dataSource);
        var first = store.findPage("ACTIVE", 2, null);
        assertThat(first.items()).extracting(member -> member.id())
                .containsExactly(new UUID(0, 3), new UUID(0, 2));
        assertThat(first.hasNext()).isTrue();
        var last = first.items().getLast();
        var second = store.findPage("ACTIVE", 2,
                new AdminCursor("users", 2, "status=ACTIVE", last.createdAt(), last.id()));
        assertThat(second.items()).extracting(member -> member.id()).containsExactly(new UUID(0, 1));
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    void adminCurationPagesFilterLatestOverridesBeforeApplyingCursor() throws Exception {
        var adminId = UUID.randomUUID();
        var at = OffsetDateTime.of(2026, 9, 24, 0, 0, 0, 0, ZoneOffset.UTC);
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.identity_admin_accounts
                         (id,email,password_hash,nickname,role,status,created_at,updated_at)
                     VALUES (?,'page-admin@example.com','hash','관리자','ADMIN','ACTIVE',?,?)
                     """)) {
            statement.setObject(1, adminId);
            statement.setObject(2, at);
            statement.setObject(3, at);
            statement.executeUpdate();
        }
        for (int suffix = 1; suffix <= 5; suffix++) {
            var placeId = new UUID(1, suffix);
            seedPlaceIdentity(placeId);
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement("""
                         INSERT INTO onmaru.catalog_admin_curation_overrides
                             (id,canonical_place_id,category,included,version,updated_by,created_at,updated_at)
                         VALUES (?,?, 'VILLAGE', ?, ?, ?, ?, ?)
                         """)) {
                statement.setObject(1, new UUID(0, suffix));
                statement.setObject(2, placeId);
                statement.setBoolean(3, suffix != 4);
                statement.setLong(4, 1);
                statement.setObject(5, adminId);
                statement.setObject(6, at);
                statement.setObject(7, at);
                statement.executeUpdate();
            }
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.catalog_admin_curation_overrides
                         (id,canonical_place_id,category,included,version,updated_by,created_at,updated_at)
                     VALUES (?,?, 'VILLAGE', false, 2, ?, ?, ?)
                     """)) {
            statement.setObject(1, new UUID(0, 6));
            statement.setObject(2, new UUID(1, 5));
            statement.setObject(3, adminId);
            statement.setObject(4, at);
            statement.setObject(5, at);
            statement.executeUpdate();
        }
        var store = new JdbcAdminCurationStore(dataSource, new ObjectMapper());
        var first = store.findPage("VILLAGE", true, 2, null);
        assertThat(first.items()).extracting(item -> item.id())
                .containsExactly(new UUID(0, 3), new UUID(0, 2));
        assertThat(first.hasNext()).isTrue();
        var last = first.items().getLast();
        var second = store.findPage("VILLAGE", true, 2,
                new AdminCursor("curations", 2, "category=VILLAGE&included=true", last.updatedAt(), last.id()));
        assertThat(second.items()).extracting(item -> item.id()).containsExactly(new UUID(0, 1));
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    void jdbcModerationQueuePagesByPriorityThenOldestSignalAndReviewId() throws Exception {
        var placeId = UUID.randomUUID();
        var authorId = UUID.randomUUID();
        var reporterId = UUID.randomUUID();
        seedPlaceIdentity(placeId);
        seedMember(authorId);
        seedMember(reporterId);
        var publicPlaceIds = new JdbcCatalogPublicPlaceIdStore(dataSource);
        publicPlaceIds.register("p-admin-queue", placeId);
        var reviews = new JdbcVisitReviewStore(dataSource, publicPlaceIds);
        var reports = new JdbcReviewReportStore(dataSource);
        var at = Instant.parse("2026-09-24T00:00:00Z");
        for (int suffix = 1; suffix <= 3; suffix++) {
            var reviewId = new UUID(0, suffix);
            reviews.add(new VisitReviewProjection(reviewId, "p-admin-queue", "장소", "kr-45-jeonju",
                    35.8151, 127.1530, "후기", null, null, List.of(), at, authorId, Set.of(),
                    VisitReviewStatus.PUBLISHED));
            reports.saveOrFindOpen(new ReviewReport(new UUID(1, suffix), reviewId, reporterId,
                    suffix == 3 ? ReviewReportReason.PERSONAL_DATA : ReviewReportReason.SPAM,
                    "신고", ReviewReportStatus.OPEN, at));
        }

        var queue = new JdbcModerationQueueReadStore(dataSource);
        var first = queue.page(2, null, at.plusSeconds(3600));
        assertThat(first.items()).extracting(item -> item.reviewId())
                .containsExactly(new UUID(0, 3), new UUID(0, 1));
        assertThat(first.items().getFirst().priority()).isEqualTo(ModerationQueuePriority.HIGH_RISK);
        assertThat(first.hasNext()).isTrue();
        var last = first.items().getLast();
        var second = queue.page(2, new AdminCursor("moderation-queue", 2, "priority=all",
                last.oldestOpenReportAt(), last.reviewId(), last.priority().name()), at.plusSeconds(3600));
        assertThat(second.items()).extracting(item -> item.reviewId()).containsExactly(new UUID(0, 2));
        assertThat(second.hasNext()).isFalse();
        assertThat(queue.oldestQueueAgeSeconds(at.plusSeconds(3600))).isEqualTo(3600);
    }

    @Test
    void adminReviewPageAtMaximumLimitUsesOneExtraRowToDetectTheNextPage() throws Exception {
        var placeId = UUID.randomUUID();
        var authorId = UUID.randomUUID();
        seedPlaceIdentity(placeId);
        seedMember(authorId);
        var publicPlaceIds = new JdbcCatalogPublicPlaceIdStore(dataSource);
        publicPlaceIds.register("p-admin-limit", placeId);
        var at = OffsetDateTime.of(2026, 9, 24, 0, 0, 0, 0, ZoneOffset.UTC);
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.community_visit_reviews
                         (id,member_id,place_id,public_place_id,place_name,region_code,
                          latitude,longitude,text,status,created_at)
                     VALUES (?,?,?,'p-admin-limit','장소','kr-45-jeonju',35.8151,127.1530,'후기','PUBLISHED',?)
                     """)) {
            for (int suffix = 1; suffix <= 101; suffix++) {
                statement.setObject(1, new UUID(0, suffix));
                statement.setObject(2, authorId);
                statement.setObject(3, placeId);
                statement.setObject(4, at);
                statement.addBatch();
            }
            statement.executeBatch();
        }

        var store = new JdbcVisitReviewStore(dataSource, publicPlaceIds);
        var first = store.findAdminPage(VisitReviewStatus.PUBLISHED, 100, null);
        assertThat(first.items()).hasSize(100);
        assertThat(first.hasNext()).isTrue();
        var last = first.items().getLast();
        var second = store.findAdminPage(VisitReviewStatus.PUBLISHED, 100,
                new AdminCursor("reviews", 100, "status=PUBLISHED", last.createdAt(), last.id()));
        assertThat(second.items()).extracting(VisitReviewProjection::id).containsExactly(new UUID(0, 1));
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    void adminReviewSearchAndStatusKeepTheCursorWithinMatchingRows() throws Exception {
        var placeId = UUID.randomUUID();
        var authorId = UUID.randomUUID();
        seedPlaceIdentity(placeId);
        seedMember(authorId);
        var publicPlaceIds = new JdbcCatalogPublicPlaceIdStore(dataSource);
        publicPlaceIds.register("p-admin-search", placeId);
        var store = new JdbcVisitReviewStore(dataSource, publicPlaceIds);
        var at = Instant.parse("2026-09-24T00:00:00Z");
        for (int suffix = 1; suffix <= 4; suffix++) {
            store.add(new VisitReviewProjection(new UUID(0, suffix), "p-admin-search", "장소", "kr-45-jeonju",
                    35.8151, 127.1530, suffix == 4 ? "카페" : "한옥 방문", null, null, List.of(), at,
                    authorId, Set.of(), suffix == 2 ? VisitReviewStatus.HIDDEN : VisitReviewStatus.PUBLISHED));
        }

        var first = store.findAdminPage(VisitReviewStatus.PUBLISHED, "한옥", 1, null);
        assertThat(first.items()).extracting(VisitReviewProjection::id).containsExactly(new UUID(0, 3));
        assertThat(first.hasNext()).isTrue();
        var last = first.items().getLast();
        var second = store.findAdminPage(VisitReviewStatus.PUBLISHED, "한옥", 1,
                new AdminCursor("reviews", 1, "status=PUBLISHED&query=한옥", last.createdAt(), last.id()));
        assertThat(second.items()).extracting(VisitReviewProjection::id).containsExactly(new UUID(0, 1));
        assertThat(second.hasNext()).isFalse();
    }

    private void seedPlaceIdentity(UUID placeId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.catalog_place_identity (id, created_at)
                     VALUES (?, ?)
                     """)) {
            statement.setObject(1, placeId);
            statement.setObject(2, OffsetDateTime.of(2026, 9, 24, 0, 0, 0, 0, ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private void seedMember(UUID memberId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.identity_members (id, status, created_at)
                     VALUES (?, 'ACTIVE', ?)
                     """)) {
            statement.setObject(1, memberId);
            statement.setObject(2, OffsetDateTime.of(2026, 9, 24, 0, 0, 0, 0, ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
    }
}

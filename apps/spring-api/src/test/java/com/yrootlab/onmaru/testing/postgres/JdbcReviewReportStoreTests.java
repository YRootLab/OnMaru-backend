package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.community.moderation.ModerationAction;
import com.yrootlab.onmaru.community.moderation.ModerationActorType;
import com.yrootlab.onmaru.community.moderation.ModerationReason;
import com.yrootlab.onmaru.community.moderation.ReviewReport;
import com.yrootlab.onmaru.community.moderation.ReviewReportReason;
import com.yrootlab.onmaru.community.moderation.ReviewReportStatus;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.persistence.community.JdbcReviewReportStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import javax.sql.DataSource;
import java.sql.DriverManager;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class JdbcReviewReportStoreTests {
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432).withEnv("POSTGRES_DB", "onmaru_test").withEnv("POSTGRES_USER", "onmaru_test").withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
    private DataSource dataSource;
    private UUID reviewId;
    private UUID reporterId;
    @BeforeAll static void start() { POSTGRES.start(); }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void reset() throws Exception {
        try (var c = DriverManager.getConnection(url(), "onmaru_test", "onmaru_test")) { PostgresTestDatabase.reset(c); }
        Flyway.configure().dataSource(url(), "onmaru_test", "onmaru_test").locations("classpath:db/migration/baseline").baselineOnMigrate(true).baselineVersion("0").load().migrate();
        dataSource = new DriverManagerDataSource(url(), "onmaru_test", "onmaru_test");
        reviewId = UUID.randomUUID(); reporterId = UUID.randomUUID(); seedReview();
    }
    @Test void persistsOpenReportsAndAuditActionsAcrossInstances() {
        var at = Instant.parse("2026-09-25T00:00:00Z");
        var report = new ReviewReport(UUID.randomUUID(), reviewId, reporterId, ReviewReportReason.SPAM, "광고", ReviewReportStatus.OPEN, at);
        var action = new ModerationAction(UUID.randomUUID(), reviewId, ModerationActorType.OPERATOR, "operator", VisitReviewStatus.PUBLISHED, VisitReviewStatus.HIDDEN, ModerationReason.SPAM_CONFIRMED, at.plusSeconds(1));
        var store = new JdbcReviewReportStore(dataSource);
        assertThat(store.saveOrFindOpen(report)).isEqualTo(report);
        store.addAudit(action);
        var reloaded = new JdbcReviewReportStore(dataSource);
        assertThat(reloaded.openReports()).containsExactly(report);
        assertThat(reloaded.auditLog()).containsExactly(action);
        reloaded.closeOpenReports(reviewId, ReviewReportStatus.RESOLVED);
        assertThat(new JdbcReviewReportStore(dataSource).openReports()).isEmpty();
    }
    private void seedReview() throws Exception {
        var author = UUID.randomUUID(); var place = UUID.randomUUID(); var at = OffsetDateTime.of(2026,9,25,0,0,0,0,ZoneOffset.UTC);
        try (var c=dataSource.getConnection(); var members=c.prepareStatement("INSERT INTO onmaru.identity_members (id,status,created_at) VALUES (?, 'ACTIVE', ?)"); var identity=c.prepareStatement("INSERT INTO onmaru.catalog_place_identity (id,created_at) VALUES (?,?)"); var review=c.prepareStatement("INSERT INTO onmaru.community_visit_reviews (id,member_id,place_id,text,status,created_at) VALUES (?,?,?,'후기','PUBLISHED',?)")) {
            for (var id : new UUID[]{author, reporterId}) { members.setObject(1,id); members.setObject(2,at); members.addBatch(); } members.executeBatch();
            identity.setObject(1,place); identity.setObject(2,at); identity.executeUpdate();
            review.setObject(1,reviewId); review.setObject(2,author); review.setObject(3,place); review.setObject(4,at); review.executeUpdate();
        }
    }
    private static String url(){return "jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/onmaru_test";}
}

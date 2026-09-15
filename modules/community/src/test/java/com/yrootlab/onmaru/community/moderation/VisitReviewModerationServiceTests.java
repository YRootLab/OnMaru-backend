package com.yrootlab.onmaru.community.moderation;

import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisitReviewModerationServiceTests {

    private static final UUID REVIEW_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private static final UUID AUTHOR_ID = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");
    private static final UUID REPORTER_ID = UUID.fromString("8ce13b1d-01bb-42ea-8457-5e0df299a5de");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void duplicateOpenReportReturnsExistingReceipt() {
        var store = new InMemoryVisitReviewStore();
        store.add(review(VisitReviewStatus.PUBLISHED));
        var service = service(store);

        ReviewReportReceipt first = service.report(REPORTER_ID, REVIEW_ID,
                new CreateReviewReportCommand(ReviewReportReason.SPAM, "반복 홍보"));
        ReviewReportReceipt second = service.report(REPORTER_ID, REVIEW_ID,
                new CreateReviewReportCommand(ReviewReportReason.ABUSE, "다른 사유"));

        assertThat(first.reportId()).isEqualTo(second.reportId());
        assertThat(first.status()).isEqualTo(ReviewReportStatus.OPEN);
        assertThat(service.openReports()).hasSize(1);
    }

    @Test
    void rejectsSelfReportInvalidReasonAndUnavailableReview() {
        var store = new InMemoryVisitReviewStore();
        store.add(review(VisitReviewStatus.PUBLISHED));
        store.add(review(UUID.fromString("00000000-0000-0000-0000-000000000124"), VisitReviewStatus.HIDDEN));
        var service = service(store);

        assertThatThrownBy(() -> service.report(AUTHOR_ID, REVIEW_ID,
                new CreateReviewReportCommand(ReviewReportReason.SPAM, null)))
                .isInstanceOf(SelfVisitReviewReportException.class);
        assertThatThrownBy(() -> service.report(REPORTER_ID, REVIEW_ID,
                new CreateReviewReportCommand(null, null)))
                .isInstanceOf(ReviewReportInvalidException.class);
        assertThatThrownBy(() -> service.report(REPORTER_ID, UUID.fromString("00000000-0000-0000-0000-000000000124"),
                new CreateReviewReportCommand(ReviewReportReason.SPAM, null)))
                .isInstanceOf(VisitReviewReportNotFoundException.class);
    }

    @Test
    void moderationTransitionWritesActorReasonBeforeAfterAuditAndUpdatesReviewStatus() {
        var store = new InMemoryVisitReviewStore();
        store.add(review(VisitReviewStatus.PUBLISHED));
        var service = service(store);

        ModerationAction action = service.moderate(
                REVIEW_ID,
                "operator-1",
                VisitReviewStatus.HIDDEN,
                ModerationReason.PII_HIGH_RISK);

        assertThat(action.actorType()).isEqualTo(ModerationActorType.OPERATOR);
        assertThat(action.actorRef()).isEqualTo("operator-1");
        assertThat(action.previousStatus()).isEqualTo(VisitReviewStatus.PUBLISHED);
        assertThat(action.nextStatus()).isEqualTo(VisitReviewStatus.HIDDEN);
        assertThat(action.reason()).isEqualTo(ModerationReason.PII_HIGH_RISK);
        assertThat(service.auditLog()).containsExactly(action);
        assertThat(store.findSnapshot().getFirst().status()).isEqualTo(VisitReviewStatus.HIDDEN);
    }

    @Test
    void systemPiiHideKeepsReportOpenAndOperatorFalsePositiveRestoreDismissesIt() {
        var reviewStore = new InMemoryVisitReviewStore();
        reviewStore.add(review(VisitReviewStatus.PUBLISHED));
        var reportStore = new InMemoryReviewReportStore();
        var service = service(reviewStore, reportStore);
        service.report(REPORTER_ID, REVIEW_ID,
                new CreateReviewReportCommand(ReviewReportReason.PERSONAL_DATA, "synthetic phone"));

        ModerationAction systemAction = service.hideHighRiskPii(REVIEW_ID, "pii-detector-v1");

        assertThat(systemAction.actorType()).isEqualTo(ModerationActorType.SYSTEM);
        assertThat(systemAction.reason()).isEqualTo(ModerationReason.PII_HIGH_RISK);
        assertThat(service.openReports()).hasSize(1);
        assertThat(reviewStore.findSnapshot().getFirst().status()).isEqualTo(VisitReviewStatus.HIDDEN);

        ModerationAction operatorAction = service.moderate(
                REVIEW_ID,
                "operator-1",
                VisitReviewStatus.PUBLISHED,
                ModerationReason.FALSE_POSITIVE);

        assertThat(operatorAction.actorType()).isEqualTo(ModerationActorType.OPERATOR);
        assertThat(service.openReports()).isEmpty();
        assertThat(service.auditLog()).containsExactly(systemAction, operatorAction);
        assertThat(reviewStore.findSnapshot().getFirst().status()).isEqualTo(VisitReviewStatus.PUBLISHED);
    }

    @Test
    void operatorRemovalResolvesEveryOpenReportForReview() {
        var reviewStore = new InMemoryVisitReviewStore();
        reviewStore.add(review(VisitReviewStatus.PUBLISHED));
        var reportStore = new InMemoryReviewReportStore();
        var service = service(reviewStore, reportStore);
        service.report(REPORTER_ID, REVIEW_ID,
                new CreateReviewReportCommand(ReviewReportReason.ABUSE, "synthetic abuse"));
        service.report(UUID.fromString("00000000-0000-0000-0000-000000000777"), REVIEW_ID,
                new CreateReviewReportCommand(ReviewReportReason.SPAM, "synthetic spam"));

        service.moderate(
                REVIEW_ID,
                "operator-1",
                VisitReviewStatus.REMOVED,
                ModerationReason.ABUSE_CONFIRMED);

        assertThat(service.openReports()).isEmpty();
        assertThat(reportStore.reports()).extracting(ReviewReport::status)
                .containsOnly(ReviewReportStatus.RESOLVED);
    }

    private VisitReviewModerationService service(InMemoryVisitReviewStore store) {
        return service(store, new InMemoryReviewReportStore());
    }

    private VisitReviewModerationService service(
            InMemoryVisitReviewStore store,
            InMemoryReviewReportStore reportStore) {
        return new VisitReviewModerationService(
                store,
                reportStore,
                () -> UUID.fromString("00000000-0000-0000-0000-000000000900"),
                () -> UUID.fromString("00000000-0000-0000-0000-000000000901"),
                CLOCK);
    }

    private VisitReviewProjection review(VisitReviewStatus status) {
        return review(REVIEW_ID, status);
    }

    private VisitReviewProjection review(UUID reviewId, VisitReviewStatus status) {
        return new VisitReviewProjection(
                reviewId,
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                "kr-45-jeonju",
                35.8151,
                127.1530,
                "비 오는 날 처마 밑에서 쉬기 좋았습니다.",
                Instant.parse("2026-09-15T02:00:00Z"),
                AUTHOR_ID,
                Set.of(),
                status);
    }
}

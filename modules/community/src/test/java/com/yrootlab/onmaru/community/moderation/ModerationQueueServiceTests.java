package com.yrootlab.onmaru.community.moderation;

import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModerationQueueServiceTests {

    private static final UUID HIGH_RISK_REVIEW_ID = UUID.fromString("00000000-0000-0000-0000-000000000139");
    private static final UUID STANDARD_REVIEW_ID = UUID.fromString("00000000-0000-0000-0000-000000000140");
    private static final UUID REPORTER_ID = UUID.fromString("8ce13b1d-01bb-42ea-8457-5e0df299a5de");
    private static final Instant NOW = Instant.parse("2026-09-15T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void projectsHighRiskBeforeOlderStandardReportWithSlaAgeAndNoReporterIdentity() {
        var reviewStore = new InMemoryVisitReviewStore();
        reviewStore.add(review(HIGH_RISK_REVIEW_ID, VisitReviewStatus.HIDDEN, "synthetic pii"));
        reviewStore.add(review(STANDARD_REVIEW_ID, VisitReviewStatus.PUBLISHED, "synthetic spam"));
        var reportStore = new InMemoryReviewReportStore();
        reportStore.saveOrFindOpen(report(
                HIGH_RISK_REVIEW_ID,
                ReviewReportReason.PERSONAL_DATA,
                "synthetic phone",
                Instant.parse("2026-09-14T02:00:00Z")));
        reportStore.saveOrFindOpen(report(
                STANDARD_REVIEW_ID,
                ReviewReportReason.SPAM,
                "synthetic link",
                Instant.parse("2026-09-13T04:00:00Z")));
        var service = new ModerationQueueService(reviewStore, reportStore, CLOCK);

        ModerationQueueSnapshot snapshot = service.snapshot(100);

        assertThat(snapshot.generatedAt()).isEqualTo(NOW);
        assertThat(snapshot.oldestOpenReportAgeSeconds()).isEqualTo(169_200);
        assertThat(snapshot.items()).extracting(ModerationQueueItem::reviewId)
                .containsExactly(HIGH_RISK_REVIEW_ID, STANDARD_REVIEW_ID);
        assertThat(snapshot.items().getFirst()).satisfies(item -> {
            assertThat(item.priority()).isEqualTo(ModerationQueuePriority.HIGH_RISK);
            assertThat(item.ageSeconds()).isEqualTo(90_000);
            assertThat(item.slaTargetAt()).isEqualTo(Instant.parse("2026-09-15T02:00:00Z"));
            assertThat(item.overdue()).isTrue();
            assertThat(item.reports()).singleElement().satisfies(report -> {
                assertThat(report.reason()).isEqualTo(ReviewReportReason.PERSONAL_DATA);
                assertThat(report.detail()).isEqualTo("synthetic phone");
            });
        });
        assertThat(ModerationQueueReport.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("reporterMemberId");
    }

    @Test
    void classifiesSystemPiiAuditAsHighRiskEvenForStandardReportReason() {
        var reviewStore = new InMemoryVisitReviewStore();
        reviewStore.add(review(HIGH_RISK_REVIEW_ID, VisitReviewStatus.HIDDEN, "synthetic hidden"));
        var reportStore = new InMemoryReviewReportStore();
        reportStore.saveOrFindOpen(report(
                HIGH_RISK_REVIEW_ID,
                ReviewReportReason.OTHER,
                null,
                Instant.parse("2026-09-15T02:30:00Z")));
        reportStore.addAudit(new ModerationAction(
                UUID.fromString("00000000-0000-0000-0000-000000001390"),
                HIGH_RISK_REVIEW_ID,
                ModerationActorType.SYSTEM,
                "pii-detector-v1",
                VisitReviewStatus.PUBLISHED,
                VisitReviewStatus.HIDDEN,
                ModerationReason.PII_HIGH_RISK,
                Instant.parse("2026-09-15T02:31:00Z")));
        var service = new ModerationQueueService(reviewStore, reportStore, CLOCK);

        ModerationQueueItem item = service.snapshot(100).items().getFirst();

        assertThat(item.priority()).isEqualTo(ModerationQueuePriority.HIGH_RISK);
        assertThat(item.slaTargetAt()).isEqualTo(Instant.parse("2026-09-16T02:30:00Z"));
        assertThat(item.priorActions()).hasSize(1);
    }

    @Test
    void queuesSystemPiiHideWithoutUserReport() {
        var reviewStore = new InMemoryVisitReviewStore();
        reviewStore.add(review(HIGH_RISK_REVIEW_ID, VisitReviewStatus.PUBLISHED, "synthetic hidden"));
        var reportStore = new InMemoryReviewReportStore();
        var actionIds = new ArrayDeque<>(List.of(
                UUID.fromString("00000000-0000-0000-0000-000000001399"),
                UUID.fromString("00000000-0000-0000-0000-000000001390")));
        var moderationService = new VisitReviewModerationService(
                reviewStore,
                reportStore,
                UUID::randomUUID,
                actionIds::removeFirst,
                CLOCK);
        moderationService.hideHighRiskPii(HIGH_RISK_REVIEW_ID, "pii-detector-v1");
        var queueService = new ModerationQueueService(reviewStore, reportStore, CLOCK);

        ModerationQueueItem item = queueService.snapshot(100).items().getFirst();

        assertThat(item.reviewId()).isEqualTo(HIGH_RISK_REVIEW_ID);
        assertThat(item.priority()).isEqualTo(ModerationQueuePriority.HIGH_RISK);
        assertThat(item.reports()).isEmpty();
        assertThat(item.oldestOpenReportAt()).isEqualTo(NOW);
        assertThat(item.slaTargetAt()).isEqualTo(NOW.plusSeconds(86_400));

        moderationService.moderate(
                HIGH_RISK_REVIEW_ID,
                "operator-1",
                VisitReviewStatus.HIDDEN,
                ModerationReason.PII_HIGH_RISK);

        assertThat(queueService.snapshot(100).items()).isEmpty();
    }

    @Test
    void rejectsUnboundedQueueLimits() {
        var service = new ModerationQueueService(
                new InMemoryVisitReviewStore(),
                new InMemoryReviewReportStore(),
                CLOCK);

        assertThatThrownBy(() -> service.snapshot(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.snapshot(101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queueSnapshotUsesReportStoreAtomicBoundary() throws Exception {
        var reviewStore = new InMemoryVisitReviewStore();
        reviewStore.add(review(STANDARD_REVIEW_ID, VisitReviewStatus.PUBLISHED, "synthetic spam"));
        var reportStore = new InMemoryReviewReportStore();
        reportStore.saveOrFindOpen(report(
                STANDARD_REVIEW_ID,
                ReviewReportReason.SPAM,
                null,
                Instant.parse("2026-09-15T02:30:00Z")));
        var service = new ModerationQueueService(reviewStore, reportStore, CLOCK);
        var lockHeld = new CountDownLatch(1);
        var releaseLock = new CountDownLatch(1);
        var snapshotAttempted = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var lockOwner = executor.submit(() -> reportStore.executeAtomically(() -> {
                lockHeld.countDown();
                await(releaseLock);
                return null;
            }));
            assertThat(lockHeld.await(1, TimeUnit.SECONDS)).isTrue();
            var snapshot = executor.submit(() -> {
                snapshotAttempted.countDown();
                return service.snapshot(100);
            });
            assertThat(snapshotAttempted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(snapshot).isNotDone();

            releaseLock.countDown();
            lockOwner.get(1, TimeUnit.SECONDS);
            assertThat(snapshot.get(1, TimeUnit.SECONDS).items()).hasSize(1);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test coordinator");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test coordinator interrupted", exception);
        }
    }

    private ReviewReport report(UUID reviewId, ReviewReportReason reason, String detail, Instant createdAt) {
        return new ReviewReport(UUID.randomUUID(), reviewId, REPORTER_ID, reason, detail, ReviewReportStatus.OPEN, createdAt);
    }

    private VisitReviewProjection review(UUID reviewId, VisitReviewStatus status, String text) {
        return new VisitReviewProjection(
                reviewId,
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                "kr-45-jeonju",
                35.8151,
                127.1530,
                text,
                Instant.parse("2026-09-13T00:00:00Z"),
                UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712"),
                Set.of(),
                status);
    }
}

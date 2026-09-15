package com.yrootlab.onmaru.community.moderation;

import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ModerationQueueService {

    private static final Duration HIGH_RISK_SLA = Duration.ofHours(24);
    private static final Duration STANDARD_SLA = Duration.ofHours(72);
    private static final int MAX_LIMIT = 100;

    private final InMemoryVisitReviewStore reviewStore;
    private final InMemoryReviewReportStore reportStore;
    private final Clock clock;

    public ModerationQueueService(
            InMemoryVisitReviewStore reviewStore,
            InMemoryReviewReportStore reportStore,
            Clock clock) {
        this.reviewStore = reviewStore;
        this.reportStore = reportStore;
        this.clock = clock;
    }

    public ModerationQueueSnapshot snapshot(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        Instant now = clock.instant();
        Map<UUID, VisitReviewProjection> reviews = reviewStore.findSnapshot().stream()
                .collect(Collectors.toMap(VisitReviewProjection::id, Function.identity()));
        Map<UUID, List<ModerationAction>> actions = reportStore.auditLog().stream()
                .collect(Collectors.groupingBy(ModerationAction::reviewId));
        List<ModerationQueueItem> projected = reportStore.openReports().stream()
                .collect(Collectors.groupingBy(ReviewReport::reviewId))
                .entrySet().stream()
                .map(entry -> project(
                        reviews.get(entry.getKey()),
                        entry.getValue(),
                        actions.getOrDefault(entry.getKey(), List.of()),
                        now))
                .sorted(queueOrder())
                .toList();
        long oldestAge = projected.stream()
                .mapToLong(ModerationQueueItem::ageSeconds)
                .max()
                .orElse(0L);
        return new ModerationQueueSnapshot(now, oldestAge, projected.stream().limit(limit).toList());
    }

    private ModerationQueueItem project(
            VisitReviewProjection review,
            List<ReviewReport> reports,
            List<ModerationAction> actions,
            Instant now) {
        if (review == null) {
            throw new VisitReviewReportNotFoundException();
        }
        List<ReviewReport> orderedReports = reports.stream()
                .sorted(Comparator.comparing(ReviewReport::createdAt).thenComparing(ReviewReport::reportId))
                .toList();
        List<ModerationAction> orderedActions = actions.stream()
                .sorted(Comparator.comparing(ModerationAction::createdAt).thenComparing(ModerationAction::actionId))
                .toList();
        Instant oldest = orderedReports.getFirst().createdAt();
        long ageSeconds = Math.max(0L, Duration.between(oldest, now).toSeconds());
        ModerationQueuePriority priority = priority(orderedReports, orderedActions);
        Instant target = oldest.plus(priority == ModerationQueuePriority.HIGH_RISK ? HIGH_RISK_SLA : STANDARD_SLA);
        return new ModerationQueueItem(
                review.id(),
                review.text(),
                review.status(),
                priority,
                orderedReports.stream()
                        .map(report -> new ModerationQueueReport(report.reason(), report.detail(), report.createdAt()))
                        .toList(),
                orderedActions,
                oldest,
                ageSeconds,
                target,
                !target.isAfter(now));
    }

    private ModerationQueuePriority priority(List<ReviewReport> reports, List<ModerationAction> actions) {
        boolean personalDataReport = reports.stream()
                .anyMatch(report -> report.reason() == ReviewReportReason.PERSONAL_DATA);
        boolean systemPiiHide = actions.stream()
                .anyMatch(action -> action.actorType() == ModerationActorType.SYSTEM
                        && action.reason() == ModerationReason.PII_HIGH_RISK);
        return personalDataReport || systemPiiHide
                ? ModerationQueuePriority.HIGH_RISK
                : ModerationQueuePriority.STANDARD;
    }

    private Comparator<ModerationQueueItem> queueOrder() {
        return Comparator.comparing(ModerationQueueItem::priority)
                .thenComparing(ModerationQueueItem::oldestOpenReportAt)
                .thenComparing(ModerationQueueItem::reviewId);
    }
}

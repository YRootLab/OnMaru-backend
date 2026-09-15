package com.yrootlab.onmaru.community.moderation;

import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        return reportStore.executeAtomically(() -> snapshotAtomically(limit));
    }

    private ModerationQueueSnapshot snapshotAtomically(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        Instant now = clock.instant();
        Map<UUID, VisitReviewProjection> reviews = reviewStore.findSnapshot().stream()
                .collect(Collectors.toMap(VisitReviewProjection::id, Function.identity()));
        Map<UUID, List<ModerationAction>> actions = reportStore.auditLog().stream()
                .collect(Collectors.groupingBy(ModerationAction::reviewId));
        Map<UUID, List<ReviewReport>> reports = reportStore.openReports().stream()
                .collect(Collectors.groupingBy(ReviewReport::reviewId));
        Set<UUID> queuedReviewIds = new HashSet<>(reports.keySet());
        actions.forEach((reviewId, reviewActions) -> pendingPiiAction(reviewActions)
                .ifPresent(ignored -> queuedReviewIds.add(reviewId)));
        List<ModerationQueueItem> projected = queuedReviewIds.stream()
                .map(reviewId -> project(
                        reviews.get(reviewId),
                        reports.getOrDefault(reviewId, List.of()),
                        actions.getOrDefault(reviewId, List.of()),
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
                .sorted(Comparator.comparing(ModerationAction::createdAt))
                .toList();
        Optional<ModerationAction> pendingPii = pendingPiiAction(orderedActions);
        Instant oldest = oldestQueueSignal(orderedReports, pendingPii);
        long ageSeconds = Math.max(0L, Duration.between(oldest, now).toSeconds());
        ModerationQueuePriority priority = priority(orderedReports, pendingPii);
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

    private Instant oldestQueueSignal(
            List<ReviewReport> reports,
            Optional<ModerationAction> pendingPii) {
        return reports.stream()
                .map(ReviewReport::createdAt)
                .min(Instant::compareTo)
                .map(reportAt -> pendingPii
                        .map(action -> action.createdAt().isBefore(reportAt) ? action.createdAt() : reportAt)
                        .orElse(reportAt))
                .orElseGet(() -> pendingPii.orElseThrow().createdAt());
    }

    private ModerationQueuePriority priority(
            List<ReviewReport> reports,
            Optional<ModerationAction> pendingPii) {
        boolean personalDataReport = reports.stream()
                .anyMatch(report -> report.reason() == ReviewReportReason.PERSONAL_DATA);
        return personalDataReport || pendingPii.isPresent()
                ? ModerationQueuePriority.HIGH_RISK
                : ModerationQueuePriority.STANDARD;
    }

    private Optional<ModerationAction> pendingPiiAction(List<ModerationAction> actions) {
        return actions.isEmpty()
                ? Optional.empty()
                : Optional.of(actions.getLast())
                .filter(action -> action.actorType() == ModerationActorType.SYSTEM)
                .filter(action -> action.reason() == ModerationReason.PII_HIGH_RISK);
    }

    private Comparator<ModerationQueueItem> queueOrder() {
        return Comparator.comparing(ModerationQueueItem::priority)
                .thenComparing(ModerationQueueItem::oldestOpenReportAt)
                .thenComparing(ModerationQueueItem::reviewId);
    }
}

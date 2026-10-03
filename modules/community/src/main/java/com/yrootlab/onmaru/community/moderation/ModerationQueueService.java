package com.yrootlab.onmaru.community.moderation;

import com.yrootlab.onmaru.community.query.VisitReviewStore;
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
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.catalog.application.pagination.AdminPage;

public final class ModerationQueueService {

    private static final Duration HIGH_RISK_SLA = Duration.ofHours(24);
    private static final Duration STANDARD_SLA = Duration.ofHours(72);
    private static final int MAX_LIMIT = 100;

    private final VisitReviewStore reviewStore;
    private final ReviewReportStore reportStore;
    private final Clock clock;
    private final ModerationQueueReadStore readStore;

    public ModerationQueueService(
            VisitReviewStore reviewStore,
            ReviewReportStore reportStore,
            Clock clock) {
        this(reviewStore, reportStore, clock, null);
    }

    public ModerationQueueService(
            VisitReviewStore reviewStore,
            ReviewReportStore reportStore,
            Clock clock,
            ModerationQueueReadStore readStore) {
        this.reviewStore = reviewStore;
        this.reportStore = reportStore;
        this.clock = clock;
        this.readStore = readStore;
    }

    public AdminPage<ModerationQueueItem> page(int limit, AdminCursor cursor) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        if (readStore != null) return readStore.page(limit, cursor, clock.instant());

        // Local/test profile only: production uses the bounded JDBC read model.
        List<ModerationQueueItem> matching = reportStore.executeAtomically(() -> allQueueItems(clock.instant()));
        List<ModerationQueueItem> items = matching.stream()
                .filter(item -> after(item, cursor))
                .limit(limit + 1L)
                .toList();
        boolean hasNext = items.size() > limit;
        long totalCount = cursor != null && cursor.totalCount() != null ? cursor.totalCount() : matching.size();
        return new AdminPage<>(items.subList(0, Math.min(limit, items.size())), hasNext, totalCount);
    }

    public long oldestQueueAgeSeconds() {
        Instant now = clock.instant();
        return readStore == null
                ? snapshot(MAX_LIMIT).oldestOpenReportAgeSeconds()
                : readStore.oldestQueueAgeSeconds(now);
    }

    public Instant generatedAt() {
        return clock.instant();
    }

    private boolean after(ModerationQueueItem item, AdminCursor cursor) {
        if (cursor == null) return true;
        int priority = item.priority().compareTo(ModerationQueuePriority.valueOf(cursor.sortGroup()));
        if (priority != 0) return priority > 0;
        int timestamp = item.oldestOpenReportAt().compareTo(cursor.timestamp());
        return timestamp > 0 || timestamp == 0 && item.reviewId().compareTo(cursor.id()) > 0;
    }

    public ModerationQueueSnapshot snapshot(int limit) {
        return reportStore.executeAtomically(() -> snapshotAtomically(limit));
    }

    private ModerationQueueSnapshot snapshotAtomically(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        Instant now = clock.instant();
        List<ModerationQueueItem> projected = allQueueItems(now);
        long oldestAge = projected.stream()
                .mapToLong(ModerationQueueItem::ageSeconds)
                .max()
                .orElse(0L);
        return new ModerationQueueSnapshot(now, oldestAge, projected.stream().limit(limit).toList());
    }

    private List<ModerationQueueItem> allQueueItems(Instant now) {
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
        return projected;
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

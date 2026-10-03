package com.yrootlab.onmaru.community.moderation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.catalog.application.pagination.AdminPage;
import java.util.Comparator;

public final class InMemoryReviewReportStore implements ReviewReportStore {

    private final List<ReviewReport> reports = new CopyOnWriteArrayList<>();
    private final List<ModerationAction> auditLog = new CopyOnWriteArrayList<>();

    public synchronized <T> T executeAtomically(Supplier<T> operation) {
        return operation.get();
    }

    public synchronized ReviewReport saveOrFindOpen(ReviewReport report) {
        var existing = findOpen(report.reviewId(), report.reporterMemberId());
        if (existing.isPresent()) {
            return existing.get();
        }
        reports.add(report);
        return report;
    }

    public List<ReviewReport> openReports() {
        return reports.stream()
                .filter(report -> report.status() == ReviewReportStatus.OPEN)
                .toList();
    }

    public AdminPage<ReviewReport> openReportsPage(ReviewReportReason reason, int limit, AdminCursor cursor) {
        var filtered = reports.stream()
                .filter(report -> report.status() == ReviewReportStatus.OPEN)
                .filter(report -> reason == null || report.reason() == reason)
                .sorted(Comparator.comparing(ReviewReport::createdAt).reversed()
                        .thenComparing(ReviewReport::reportId, Comparator.reverseOrder()))
                .toList();
        var items = filtered.stream()
                .filter(report -> cursor == null || report.createdAt().isBefore(cursor.timestamp())
                        || report.createdAt().equals(cursor.timestamp()) && report.reportId().compareTo(cursor.id()) < 0)
                .limit(limit + 1L).toList();
        boolean hasNext = items.size() > limit;
        long totalCount = cursor != null && cursor.totalCount() != null ? cursor.totalCount() : filtered.size();
        return new AdminPage<>(items.subList(0, Math.min(limit, items.size())), hasNext, totalCount);
    }

    public List<ReviewReport> reports() {
        return List.copyOf(reports);
    }

    public synchronized void closeOpenReports(UUID reviewId, ReviewReportStatus status) {
        if (status == ReviewReportStatus.OPEN) {
            throw new IllegalArgumentException("closed report status is required");
        }
        for (int index = 0; index < reports.size(); index++) {
            ReviewReport current = reports.get(index);
            if (current.reviewId().equals(reviewId) && current.status() == ReviewReportStatus.OPEN) {
                reports.set(index, new ReviewReport(
                        current.reportId(),
                        current.reviewId(),
                        current.reporterMemberId(),
                        current.reason(),
                        current.detail(),
                        status,
                        current.createdAt()));
            }
        }
    }

    public void addAudit(ModerationAction action) {
        auditLog.add(action);
    }

    public List<ModerationAction> auditLog() {
        return List.copyOf(auditLog);
    }

    public void clear() {
        reports.clear();
        auditLog.clear();
    }

    private Optional<ReviewReport> findOpen(UUID reviewId, UUID reporterMemberId) {
        return reports.stream()
                .filter(report -> report.reviewId().equals(reviewId))
                .filter(report -> report.reporterMemberId().equals(reporterMemberId))
                .filter(report -> report.status() == ReviewReportStatus.OPEN)
                .findFirst();
    }
}

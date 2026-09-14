package com.yrootlab.onmaru.community.moderation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryReviewReportStore {

    private final List<ReviewReport> reports = new CopyOnWriteArrayList<>();
    private final List<ModerationAction> auditLog = new CopyOnWriteArrayList<>();

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

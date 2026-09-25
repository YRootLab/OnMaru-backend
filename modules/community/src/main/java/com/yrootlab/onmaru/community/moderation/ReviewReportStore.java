package com.yrootlab.onmaru.community.moderation;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public interface ReviewReportStore {
    <T> T executeAtomically(Supplier<T> operation);
    ReviewReport saveOrFindOpen(ReviewReport report);
    List<ReviewReport> openReports();
    void closeOpenReports(UUID reviewId, ReviewReportStatus status);
    void addAudit(ModerationAction action);
    List<ModerationAction> auditLog();
}

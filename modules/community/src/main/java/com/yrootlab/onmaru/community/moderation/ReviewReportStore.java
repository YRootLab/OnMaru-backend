package com.yrootlab.onmaru.community.moderation;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.catalog.application.pagination.AdminPage;

public interface ReviewReportStore {
    <T> T executeAtomically(Supplier<T> operation);
    ReviewReport saveOrFindOpen(ReviewReport report);
    List<ReviewReport> openReports();
    default AdminPage<ReviewReport> openReportsPage(int limit, AdminCursor cursor) {
        return openReportsPage(null, limit, cursor);
    }
    AdminPage<ReviewReport> openReportsPage(ReviewReportReason reason, int limit, AdminCursor cursor);
    void closeOpenReports(UUID reviewId, ReviewReportStatus status);
    void addAudit(ModerationAction action);
    List<ModerationAction> auditLog();
}

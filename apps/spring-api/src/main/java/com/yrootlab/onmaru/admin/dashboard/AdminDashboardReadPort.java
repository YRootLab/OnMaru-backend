package com.yrootlab.onmaru.admin.dashboard;

import java.time.Instant;
import java.util.List;

public interface AdminDashboardReadPort {
    AdminDashboardMetrics metrics(Instant fromInclusive, Instant toExclusive);
    List<AdminDashboardService.ReviewSummary> recentReviews(Instant fromInclusive, Instant toExclusive, int limit);
    List<AdminDashboardService.ReportSummary> recentOpenReports(Instant fromInclusive, Instant toExclusive, int limit);

    record AdminDashboardMetrics(long total, long published, long hidden, long removed, long pendingReports) {
    }
}

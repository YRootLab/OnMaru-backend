package com.yrootlab.onmaru.admin.dashboard;

import com.yrootlab.onmaru.community.moderation.ReviewReport;
import com.yrootlab.onmaru.community.moderation.ReviewReportStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.admin.pipeline.AdminPipelinePort;
import com.yrootlab.onmaru.community.query.VisitReviewStore;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class AdminDashboardService {

    private final VisitReviewStore reviewStore;
    private final ReviewReportStore reportStore;
    private final AdminPipelinePort pipeline;
    private final AdminDashboardReadPort readPort;

    public AdminDashboardService(VisitReviewStore reviewStore, ReviewReportStore reportStore) {
        this(reviewStore, reportStore, null, null);
    }

    public AdminDashboardService(
            VisitReviewStore reviewStore, ReviewReportStore reportStore, AdminPipelinePort pipeline) {
        this(reviewStore, reportStore, pipeline, null);
    }

    public AdminDashboardService(
            VisitReviewStore reviewStore,
            ReviewReportStore reportStore,
            AdminPipelinePort pipeline,
            AdminDashboardReadPort readPort) {
        this.reviewStore = reviewStore;
        this.reportStore = reportStore;
        this.pipeline = pipeline;
        this.readPort = readPort;
    }

    public AdminDashboardSummary summarize(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("dashboard date range is invalid");
        }
        Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endExclusive = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        if (readPort != null) {
            var metrics = readPort.metrics(start, endExclusive);
            return new AdminDashboardSummary(from, to, List.of(
                    new DashboardMetric("REVIEWS_TOTAL", metrics.total()),
                    new DashboardMetric("REVIEWS_PUBLISHED", metrics.published()),
                    new DashboardMetric("REVIEWS_HIDDEN", metrics.hidden()),
                    new DashboardMetric("REVIEWS_REMOVED", metrics.removed()),
                    new DashboardMetric("REPORTS_PENDING", metrics.pendingReports())),
                    readPort.recentReviews(start, endExclusive, 5),
                    readPort.recentOpenReports(start, endExclusive, 5), pipelineStatus());
        }
        List<VisitReviewProjection> reviews = reviewStore.findSnapshot().stream()
                .filter(review -> inRange(review.createdAt(), start, endExclusive))
                .sorted(Comparator.comparing(VisitReviewProjection::createdAt).reversed())
                .toList();
        List<ReviewReport> reports = reportStore.openReports().stream()
                .filter(report -> inRange(report.createdAt(), start, endExclusive))
                .sorted(Comparator.comparing(ReviewReport::createdAt).reversed())
                .toList();
        long published = reviews.stream().filter(review -> review.status() == VisitReviewStatus.PUBLISHED).count();
        long hidden = reviews.stream().filter(review -> review.status() == VisitReviewStatus.HIDDEN).count();
        long removed = reviews.stream().filter(review -> review.status() == VisitReviewStatus.REMOVED).count();
        return new AdminDashboardSummary(
                from,
                to,
                List.of(
                        new DashboardMetric("REVIEWS_TOTAL", reviews.size()),
                        new DashboardMetric("REVIEWS_PUBLISHED", published),
                        new DashboardMetric("REVIEWS_HIDDEN", hidden),
                        new DashboardMetric("REVIEWS_REMOVED", removed),
                        new DashboardMetric("REPORTS_PENDING", reports.size())),
                reviews.stream().limit(5).map(ReviewSummary::from).toList(),
                reports.stream().limit(5).map(ReportSummary::from).toList(),
                pipelineStatus());
    }

    private PipelineStatus pipelineStatus() {
        if (pipeline == null) {
            return new PipelineStatus("kto-korean-tour", "MISSING", null, 0);
        }
        var status = pipeline.status("kto-korean-tour");
        return new PipelineStatus(status.dataset(), status.status(), status.lastSuccessAt(), status.failureCount());
    }

    private boolean inRange(Instant value, Instant start, Instant endExclusive) {
        return !value.isBefore(start) && value.isBefore(endExclusive);
    }

    public record AdminDashboardSummary(
            LocalDate from,
            LocalDate to,
            List<DashboardMetric> stats,
            List<ReviewSummary> recentReviews,
            List<ReportSummary> pendingReports,
            PipelineStatus pipeline) {
    }

    public record DashboardMetric(String key, long value) {
    }

    public record ReviewSummary(
            java.util.UUID id, String status, String content, Instant createdAt) {
        static ReviewSummary from(VisitReviewProjection review) {
            return new ReviewSummary(review.id(), review.status().name(), review.text(), review.createdAt());
        }
    }

    public record ReportSummary(
            java.util.UUID id, java.util.UUID reviewId, String reason, String status, Instant createdAt) {
        static ReportSummary from(ReviewReport report) {
            return new ReportSummary(report.reportId(), report.reviewId(), report.reason().name(), report.status().name(), report.createdAt());
        }
    }

    public record PipelineStatus(String dataset, String status, Instant lastSuccessAt, long failureCount) {
    }
}

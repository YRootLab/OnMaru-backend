package com.yrootlab.onmaru.admin.dashboard;

import com.yrootlab.onmaru.community.moderation.InMemoryReviewReportStore;
import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.admin.pipeline.AdminPipelinePort;
import com.yrootlab.onmaru.admin.pipeline.AdminPipelineRunResult;
import com.yrootlab.onmaru.admin.pipeline.AdminPipelineStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminDashboardServiceTests {

    @Test
    void summarizesReviewsWithinInclusiveDateRange() {
        var reviews = new InMemoryVisitReviewStore();
        reviews.add(review("00000000-0000-0000-0000-000000000001", "2026-09-29T01:00:00Z", VisitReviewStatus.PUBLISHED));
        reviews.add(review("00000000-0000-0000-0000-000000000002", "2026-09-28T01:00:00Z", VisitReviewStatus.HIDDEN));
        reviews.add(review("00000000-0000-0000-0000-000000000003", "2026-09-27T23:59:59Z", VisitReviewStatus.REMOVED));

        var result = new AdminDashboardService(reviews, new InMemoryReviewReportStore())
                .summarize(LocalDate.parse("2026-09-28"), LocalDate.parse("2026-09-29"));

        assertThat(result.recentReviews()).hasSize(2);
        assertThat(result.stats()).extracting(AdminDashboardService.DashboardMetric::key)
                .containsExactly("REVIEWS_TOTAL", "REVIEWS_PUBLISHED", "REVIEWS_HIDDEN", "REVIEWS_REMOVED", "REPORTS_PENDING");
        assertThat(result.stats()).extracting(AdminDashboardService.DashboardMetric::value)
                .containsExactly(2L, 1L, 1L, 0L, 0L);
        assertThat(result.pipeline().status()).isEqualTo("MISSING");
    }

    @Test
    void rejectsReversedDateRange() {
        var service = new AdminDashboardService(new InMemoryVisitReviewStore(), new InMemoryReviewReportStore());

        assertThatThrownBy(() -> service.summarize(
                LocalDate.parse("2026-09-30"), LocalDate.parse("2026-09-29")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesLivePipelineStatusInDashboardSummary() {
        var pipeline = new AdminPipelinePort() {
            @Override public AdminPipelineStatus status(String dataset) {
                return new AdminPipelineStatus(dataset, "RUNNING", null, 2);
            }

            @Override public AdminPipelineRunResult run(String dataset) {
                return new AdminPipelineRunResult(UUID.randomUUID(), "QUEUED");
            }
        };

        var result = new AdminDashboardService(
                new InMemoryVisitReviewStore(), new InMemoryReviewReportStore(), pipeline)
                .summarize(LocalDate.parse("2026-09-29"), LocalDate.parse("2026-09-29"));

        assertThat(result.pipeline().status()).isEqualTo("RUNNING");
        assertThat(result.pipeline().failureCount()).isEqualTo(2);
    }

    private VisitReviewProjection review(String id, String createdAt, VisitReviewStatus status) {
        return new VisitReviewProjection(
                UUID.fromString(id), "place", "장소", "kr-11-jongno", 37.5, 127.0,
                "후기", Instant.parse(createdAt), UUID.randomUUID(), Set.of(), status);
    }
}

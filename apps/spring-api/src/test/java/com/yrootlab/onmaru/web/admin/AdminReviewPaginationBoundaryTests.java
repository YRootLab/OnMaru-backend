package com.yrootlab.onmaru.web.admin;

import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.admin.auth.AdminJwtTokenCodec;
import com.yrootlab.onmaru.admin.auth.AdminPrincipal;
import com.yrootlab.onmaru.admin.auth.AdminRole;
import com.yrootlab.onmaru.admin.pagination.AdminCursorCodec;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.catalog.application.pagination.AdminPage;
import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.community.query.VisitReviewStore;
import com.yrootlab.onmaru.community.moderation.InMemoryReviewReportStore;
import com.yrootlab.onmaru.community.moderation.ReviewReport;
import com.yrootlab.onmaru.community.moderation.ReviewReportReason;
import com.yrootlab.onmaru.community.moderation.ReviewReportStatus;
import com.yrootlab.onmaru.community.moderation.VisitReviewModerationService;
import com.yrootlab.onmaru.config.secrets.FakeSecretProvider;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class AdminReviewPaginationBoundaryTests {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final FakeSecretProvider secrets = new FakeSecretProvider();
    private final AdminJwtTokenCodec jwt = new AdminJwtTokenCodec(
            secrets, "admin.jwt-signing-key", "onmaru-admin", "onmaru-admin-web", Duration.ofMinutes(15), CLOCK);
    private final String bearer = "Bearer " + jwt.issue(new AdminPrincipal(
            UUID.fromString("00000000-0000-0000-0000-000000000501"), "admin@example.com", AdminRole.ADMIN));

    @Test
    void reviewSearchPagesWithoutLoadingASnapshotAndRejectsCursorFromAnotherFilter() {
        var inMemory = new InMemoryVisitReviewStore();
        inMemory.add(review(3, "한옥 방문"));
        inMemory.add(review(2, "카페 방문"));
        inMemory.add(review(1, "한옥 산책"));
        VisitReviewStore store = new VisitReviewStore() {
            @Override public List<VisitReviewProjection> findSnapshot() {
                throw new AssertionError("admin list must not load the full review snapshot");
            }
            @Override public AdminPage<VisitReviewProjection> findAdminPage(
                    VisitReviewStatus status, String query, int limit, AdminCursor cursor) {
                return inMemory.findAdminPage(status, query, limit, cursor);
            }
        };
        var controller = new AdminReviewController(new AdminAuthenticator(jwt), store, null, null, null, null,
                new AdminCursorCodec(secrets, "admin.cursor-signing-key", CLOCK));
        var request = new MockHttpServletRequest();

        var first = controller.reviews("PUBLISHED", "한옥", 1, null, bearer, request);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        var firstBody = (Map<?, ?>) first.getBody();
        assertThat(firstBody.get("hasNext")).isEqualTo(true);
        assertThat(firstBody.get("totalCount")).isEqualTo(2L);
        String cursor = (String) firstBody.get("nextCursor");
        var second = controller.reviews("PUBLISHED", "한옥", 1, cursor, bearer, request);
        var secondBody = (Map<?, ?>) second.getBody();
        assertThat(secondBody.get("hasNext")).isEqualTo(false);
        assertThat(secondBody.get("totalCount")).isEqualTo(2L);

        var changedFilter = controller.reviews("PUBLISHED", "카페", 1, cursor, bearer, request);
        assertThat(changedFilter.getStatusCode().value()).isEqualTo(400);
        assertThat(((ApiErrorResponse) changedFilter.getBody()).code()).isEqualTo("VALIDATION_ERROR");
        var emptyCursor = controller.reviews("PUBLISHED", "한옥", 1, "", bearer, request);
        assertThat(emptyCursor.getStatusCode().value()).isEqualTo(400);
        assertThat(((ApiErrorResponse) emptyCursor.getBody()).code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void reportReasonPagesWithoutLoadingAllReportsAndRejectsInvalidCursor() {
        var reports = spy(new InMemoryReviewReportStore());
        for (int suffix = 1; suffix <= 3; suffix++) {
            reports.saveOrFindOpen(new ReviewReport(new UUID(0, suffix), new UUID(1, suffix), new UUID(2, 1),
                    suffix == 2 ? ReviewReportReason.ABUSE : ReviewReportReason.SPAM,
                    "신고", ReviewReportStatus.OPEN, NOW));
        }
        doThrow(new AssertionError("admin list must not load all reports")).when(reports).openReports();
        var moderation = new VisitReviewModerationService(null, reports, null, null, CLOCK);
        var controller = new AdminReviewController(new AdminAuthenticator(jwt), null, moderation, null, null, null,
                new AdminCursorCodec(secrets, "admin.cursor-signing-key", CLOCK));
        var request = new MockHttpServletRequest();

        var first = controller.reports("SPAM", 1, null, bearer, request);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        var firstBody = (Map<?, ?>) first.getBody();
        assertThat(firstBody.get("hasNext")).isEqualTo(true);
        assertThat(firstBody.get("totalCount")).isEqualTo(2L);
        String cursor = (String) firstBody.get("nextCursor");
        var second = controller.reports("SPAM", 1, cursor, bearer, request);
        var secondBody = (Map<?, ?>) second.getBody();
        assertThat(secondBody.get("hasNext")).isEqualTo(false);
        assertThat(secondBody.get("totalCount")).isEqualTo(2L);

        var changedReason = controller.reports("ABUSE", 1, cursor, bearer, request);
        assertThat(changedReason.getStatusCode().value()).isEqualTo(400);
        assertThat(((ApiErrorResponse) changedReason.getBody()).code()).isEqualTo("VALIDATION_ERROR");
        var corrupt = controller.reports("SPAM", 1, "bad-cursor", bearer, request);
        assertThat(corrupt.getStatusCode().value()).isEqualTo(400);
        assertThat(((ApiErrorResponse) corrupt.getBody()).code()).isEqualTo("VALIDATION_ERROR");
    }

    private VisitReviewProjection review(int suffix, String text) {
        return new VisitReviewProjection(new UUID(0, suffix), "p-admin-page", "장소", "kr-45-jeonju",
                35.8151, 127.1530, text, null, null, List.of(), NOW,
                new UUID(1, 1), Set.of(), VisitReviewStatus.PUBLISHED);
    }
}

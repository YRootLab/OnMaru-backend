package com.yrootlab.onmaru.web.admin;

import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.admin.dashboard.AdminDashboardService;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Tag(name = "AdminDashboard", description = "관리자 대시보드 집계")
@RestController
public final class AdminDashboardController {

    private final AdminAuthenticator authenticator;
    private final AdminDashboardService dashboardService;

    public AdminDashboardController(AdminAuthenticator authenticator, AdminDashboardService dashboardService) {
        this.authenticator = authenticator;
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "관리자 대시보드 요약 조회")
    @GetMapping("/api/v1/admin/dashboard/summary")
    public ResponseEntity<?> summary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        try {
            authenticator.authenticate(authorization);
            LocalDate end = to == null || to.isBlank() ? LocalDate.now(java.time.Clock.systemUTC()) : LocalDate.parse(to);
            LocalDate start = from == null || from.isBlank() ? end.minusDays(6) : LocalDate.parse(from);
            var summary = dashboardService.summarize(start, end);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                    "schemaVersion", "1.0",
                    "range", Map.of("from", summary.from(), "to", summary.to()),
                    "stats", summary.stats(),
                    "recentReviews", summary.recentReviews(),
                    "pendingReports", summary.pendingReports(),
                    "pipeline", summary.pipeline()));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request);
        } catch (RuntimeException exception) {
            return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        }
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        String requestId = attribute instanceof String text && !text.isBlank()
                ? text : UUID.randomUUID().toString();
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("1.2", code, code, requestId, Map.of()));
    }
}

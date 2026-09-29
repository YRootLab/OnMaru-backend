package com.yrootlab.onmaru.web.admin;

import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.admin.audit.AdminAuditLogService;
import com.yrootlab.onmaru.community.moderation.ModerationQueueService;
import com.yrootlab.onmaru.community.moderation.ModerationReason;
import com.yrootlab.onmaru.community.moderation.ReviewReport;
import com.yrootlab.onmaru.community.moderation.VisitReviewModerationService;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.community.query.VisitReviewStore;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyCommand;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyFingerprint;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKey;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyService;
import com.yrootlab.onmaru.web.common.idempotency.IdempotentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "AdminReviews", description = "관리자 후기 검수와 신고 큐")
@RestController
public final class AdminReviewController {

    private final AdminAuthenticator authenticator;
    private final VisitReviewStore reviewStore;
    private final VisitReviewModerationService moderationService;
    private final ModerationQueueService queueService;
    private final AdminAuditLogService auditLogs;
    private final IdempotencyService idempotency;

    public AdminReviewController(
            AdminAuthenticator authenticator,
            VisitReviewStore reviewStore,
            VisitReviewModerationService moderationService,
            ModerationQueueService queueService,
            AdminAuditLogService auditLogs,
            IdempotencyService idempotency) {
        this.authenticator = authenticator;
        this.reviewStore = reviewStore;
        this.moderationService = moderationService;
        this.queueService = queueService;
        this.auditLogs = auditLogs;
        this.idempotency = idempotency;
    }

    @Operation(summary = "관리자 후기 목록 조회")
    @GetMapping("/api/v1/admin/reviews")
    public ResponseEntity<?> reviews(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        try {
            authenticator.authenticate(authorization);
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException();
            }
            VisitReviewStatus requestedStatus = status == null || status.isBlank()
                    ? null : VisitReviewStatus.valueOf(status.toUpperCase());
            List<ReviewResponse> items = reviewStore.findSnapshot().stream()
                    .filter(review -> requestedStatus == null || review.status() == requestedStatus)
                    .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
                    .limit(limit)
                    .map(ReviewResponse::from)
                    .toList();
            return ok(Map.of("schemaVersion", "1.0", "items", items, "hasMore", false));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request);
        } catch (RuntimeException exception) {
            return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        }
    }

    @Operation(summary = "관리자 미처리 신고 목록 조회")
    @GetMapping("/api/v1/admin/reports")
    public ResponseEntity<?> reports(
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        try {
            authenticator.authenticate(authorization);
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException();
            }
            List<ReportResponse> items = moderationService.openReports().stream()
                    .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
                    .limit(limit)
                    .map(ReportResponse::from)
                    .toList();
            return ok(Map.of("schemaVersion", "1.0", "items", items, "hasMore", false));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request);
        } catch (RuntimeException exception) {
            return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        }
    }

    @Operation(summary = "관리자 후기 검수 상태 변경")
    @PostMapping("/api/v1/admin/reviews/{reviewId}/moderation-actions")
    public ResponseEntity<?> moderate(
            @PathVariable UUID reviewId,
            @RequestBody ModerationRequest body,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            HttpServletRequest request) {
        try {
            var principal = authenticator.authenticate(authorization);
            if (body == null || body.nextStatus() == null || body.reason() == null) {
                throw new IllegalArgumentException();
            }
            var key = IdempotencyKey.fromHeader(idempotencyKey);
            var command = new ModerationRequest(body.nextStatus(), body.reason());
            var response = idempotency.execute(new IdempotencyCommand(
                    key.value(), principal.id().toString(), "POST",
                    "/api/v1/admin/reviews/" + reviewId + "/moderation-actions",
                    IdempotencyFingerprint.sha256("POST", reviewId.toString(), "admin.review.moderate", command)), () -> {
                var action = moderationService.moderate(
                        reviewId, principal.email(), VisitReviewStatus.valueOf(body.nextStatus().toUpperCase()),
                        ModerationReason.valueOf(body.reason().toUpperCase()));
                auditLogs.append(principal, "REVIEW_MODERATED", "review", reviewId.toString(),
                        body.reason(), null, Map.of("status", action.previousStatus()),
                        Map.of("status", action.nextStatus()), requestId(request));
                return IdempotentResponse.ok(Map.of(
                        "schemaVersion", "1.0", "actionId", action.actionId(), "reviewId", action.reviewId(),
                        "previousStatus", action.previousStatus(), "nextStatus", action.nextStatus(),
                        "createdAt", action.createdAt()));
            });
            return ResponseEntity.status(response.status()).cacheControl(CacheControl.noStore()).body(response.body());
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request);
        } catch (RuntimeException exception) {
            if (exception.getClass().getSimpleName().contains("NotFound")) {
                return error(HttpStatus.NOT_FOUND, "NOT_FOUND", request);
            }
            return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        }
    }

    private UUID requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        try {
            return value == null ? null : UUID.fromString(value.toString());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    @Operation(summary = "관리자 moderation queue 조회")
    @GetMapping("/api/v1/admin/moderation/queue")
    public ResponseEntity<?> queue(
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        try {
            authenticator.authenticate(authorization);
            var snapshot = queueService.snapshot(limit);
            return ok(snapshot);
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request);
        } catch (RuntimeException exception) {
            return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        }
    }

    private ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        String requestId = attribute instanceof String text && !text.isBlank()
                ? text : UUID.randomUUID().toString();
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("1.2", code, code, requestId, Map.of()));
    }

    private record ModerationRequest(String nextStatus, String reason) {
    }

    private record ReviewResponse(
            UUID id, String status, String content, UUID authorId, Instant createdAt) {
        static ReviewResponse from(VisitReviewProjection review) {
            return new ReviewResponse(review.id(), review.status().name(), review.text(), review.authorMemberId(), review.createdAt());
        }
    }

    private record ReportResponse(
            UUID id, UUID reviewId, String reason, String detail, String status, Instant createdAt) {
        static ReportResponse from(ReviewReport report) {
            return new ReportResponse(
                    report.reportId(), report.reviewId(), report.reason().name(), report.detail(),
                    report.status().name(), report.createdAt());
        }
    }
}

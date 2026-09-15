package com.yrootlab.onmaru.web.review.moderation;

import com.yrootlab.onmaru.community.moderation.CreateReviewReportCommand;
import com.yrootlab.onmaru.community.moderation.ModerationReason;
import com.yrootlab.onmaru.community.moderation.ReviewReportInvalidException;
import com.yrootlab.onmaru.community.moderation.ReviewReportReason;
import com.yrootlab.onmaru.community.moderation.SelfVisitReviewReportException;
import com.yrootlab.onmaru.community.moderation.VisitReviewModerationService;
import com.yrootlab.onmaru.community.moderation.VisitReviewReportNotFoundException;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.operations.moderation.queue.OperatorAuthenticator;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyCommand;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyFingerprint;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKey;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyService;
import com.yrootlab.onmaru.web.common.idempotency.IdempotentResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
public final class VisitReviewModerationController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final VisitReviewModerationService moderationService;
    private final MemberLifecycleService memberLifecycleService;
    private final IdempotencyService idempotencyService;
    private final OperatorAuthenticator operatorAuthenticator;

    VisitReviewModerationController(
            VisitReviewModerationService moderationService,
            MemberLifecycleService memberLifecycleService,
            IdempotencyService idempotencyService,
            OperatorAuthenticator operatorAuthenticator) {
        this.moderationService = moderationService;
        this.memberLifecycleService = memberLifecycleService;
        this.idempotencyService = idempotencyService;
        this.operatorAuthenticator = operatorAuthenticator;
    }

    @PostMapping("/api/v1/visit-reviews/{reviewId}/reports")
    ResponseEntity<?> report(
            @PathVariable UUID reviewId,
            @RequestBody ReportRequest body,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return memberLifecycleService.currentMember(sessionToken)
                .<ResponseEntity<?>>map(member -> reportForMember(member.id(), reviewId, body, idempotencyKey, request))
                .orElseGet(() -> authRequired(request));
    }

    @PostMapping("/api/v1/operations/moderation/visit-reviews/{reviewId}")
    ResponseEntity<?> moderate(
            @PathVariable UUID reviewId,
            @RequestBody ModerationRequest body,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestHeader(name = "X-OnMaru-Operator", required = false) String operator,
            HttpServletRequest request) {
        String actorRef = operatorAuthenticator.authenticate(authorization, operator).actorRef();
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(moderationService.moderate(
                            reviewId,
                            actorRef,
                            VisitReviewStatus.valueOf(body.nextStatus()),
                            ModerationReason.valueOf(body.reason())));
        } catch (IllegalArgumentException | ReviewReportInvalidException exception) {
            return validationError(request, "moderation");
        } catch (VisitReviewReportNotFoundException exception) {
            return notFound(request);
        }
    }

    private ResponseEntity<?> reportForMember(
            UUID memberId,
            UUID reviewId,
            ReportRequest body,
            String idempotencyKey,
            HttpServletRequest request) {
        try {
            var command = new CreateReviewReportCommand(
                    body == null || body.reason() == null ? null : ReviewReportReason.valueOf(body.reason()),
                    body == null ? null : body.detail());
            var path = request.getRequestURI();
            var fingerprint = IdempotencyFingerprint.sha256(request.getMethod(), path, memberId.toString(), command);
            var response = idempotencyService.execute(new IdempotencyCommand(
                    IdempotencyKey.fromHeader(idempotencyKey).value(),
                    memberId.toString(),
                    request.getMethod(),
                    path,
                    fingerprint), () -> new IdempotentResponse(
                    202,
                    Map.of(),
                    moderationService.report(memberId, reviewId, command)));
            return toResponse(response);
        } catch (IllegalArgumentException | ReviewReportInvalidException exception) {
            return validationError(request, "reason");
        } catch (SelfVisitReviewReportException exception) {
            return selfReportForbidden(request);
        } catch (VisitReviewReportNotFoundException exception) {
            return notFound(request);
        }
    }

    private ResponseEntity<?> toResponse(IdempotentResponse response) {
        var builder = ResponseEntity.status(response.status())
                .cacheControl(CacheControl.noStore());
        response.headers().forEach(builder::header);
        return builder.body(response.body());
    }

    private ResponseEntity<ApiErrorResponse> authRequired(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("1.2", "AUTH_REQUIRED", "Authentication is required.", requestId(request), Map.of()));
    }

    private ResponseEntity<ApiErrorResponse> validationError(HttpServletRequest request, String field) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of(ApiErrorCode.VALIDATION_ERROR, requestId(request), Map.of("field", field)));
    }

    private ResponseEntity<ApiErrorResponse> selfReportForbidden(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "SELF_REPORT_FORBIDDEN",
                        "Cannot report your own review.",
                        requestId(request),
                        Map.of()));
    }

    private ResponseEntity<ApiErrorResponse> notFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of(ApiErrorCode.NOT_FOUND, requestId(request), Map.of()));
    }

    private String requestId(HttpServletRequest request) {
        var fromAttribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (fromAttribute instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        var fromHeader = request.getHeader(RequestIdFilter.HEADER);
        return fromHeader == null || fromHeader.isBlank() ? UUID.randomUUID().toString() : fromHeader;
    }

    record ReportRequest(String reason, String detail) {
    }

    record ModerationRequest(String nextStatus, String reason) {
    }
}

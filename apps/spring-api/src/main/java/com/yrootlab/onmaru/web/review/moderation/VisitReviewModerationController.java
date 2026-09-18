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
import com.yrootlab.onmaru.web.moderation.queue.OperatorAuthenticator;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyCommand;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyFingerprint;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKey;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyService;
import com.yrootlab.onmaru.web.common.idempotency.IdempotentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "03. 지도 & 방문 후기 (Map & Reviews)", description = "방문 후기 작성, 조회, 좋아요, 신고, 행정구역별 지도 통계 API")
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

    @Operation(
            summary = "방문 후기 신고 접수",
            description = "부적절하거나 유해한 방문 후기를 신고합니다. (본인 작성 후기는 신고 불가)"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "신고 접수 완료"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 신고 사유", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "본인 작성 후기 신고 금지", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "후기를 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/api/v1/visit-reviews/{reviewId}/reports")
    ResponseEntity<?> report(
            @Parameter(description = "신고할 후기 UUID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID reviewId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "신고 요청 본문", required = true)
            @RequestBody ReportRequest body,
            @Parameter(description = "멱등성 키", example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return memberLifecycleService.currentMember(sessionToken)
                .<ResponseEntity<?>>map(member -> reportForMember(member.id(), reviewId, body, idempotencyKey, request))
                .orElseGet(() -> authRequired(request));
    }

    @Operation(
            summary = "운영자 후기 검수 및 상태 변경",
            description = "운영자가 신고되거나 검수 대기 중인 후기를 승인(PUBLISHED), 숨김(HIDDEN), 삭제(DELETED) 처리합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검수 상태 변경 완료"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 검수 파라미터", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "운영자 인증 실패", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "후기를 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/api/v1/operations/moderation/visit-reviews/{reviewId}")
    ResponseEntity<?> moderate(
            @Parameter(description = "검수할 후기 UUID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID reviewId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "검수 조치 본문", required = true)
            @RequestBody ModerationRequest body,
            @Parameter(description = "운영자 인증 헤더 (Bearer 토큰)")
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Parameter(description = "운영자 식별자 (ID/이메일)", example = "operator-admin")
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

    @Schema(description = "방문 후기 신고 요청 DTO")
    record ReportRequest(
            @Schema(description = "신고 사유 (SPAM, ABUSE, OFFENSIVE, COPYRIGHT, PRIVACY, OTHER)", example = "SPAM")
            String reason,
            @Schema(description = "신고 상세 설명", example = "장소와 전혀 무관한 광고성 글입니다.")
            String detail) {
    }

    @Schema(description = "운영자 검수 조치 요청 DTO")
    record ModerationRequest(
            @Schema(description = "변경할 다음 상태 (PUBLISHED, HIDDEN, DELETED)", example = "HIDDEN")
            String nextStatus,
            @Schema(description = "조치 사유 (POLICY_VIOLATION, SPAM_CONFIRMED, FALSE_REPORT, OTHER)", example = "SPAM_CONFIRMED")
            String reason) {
    }
}

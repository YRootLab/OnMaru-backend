package com.yrootlab.onmaru.web.review.command;

import com.yrootlab.onmaru.community.command.review.CreateVisitReviewCommand;
import com.yrootlab.onmaru.community.command.review.VisitReviewCommandService;
import com.yrootlab.onmaru.community.command.review.VisitReviewNotFoundException;
import com.yrootlab.onmaru.community.command.review.VisitReviewPlaceNotEligibleException;
import com.yrootlab.onmaru.community.command.review.VisitReviewTextInvalidException;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Tag(name = "03. 지도 & 방문 후기 (Map & Reviews)", description = "방문 후기 작성, 조회, 좋아요, 신고, 행정구역별 지도 통계 API")
@RestController
public final class VisitReviewCommandController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final VisitReviewCommandService commandService;
    private final MemberLifecycleService memberLifecycleService;
    private final IdempotencyService idempotencyService;

    VisitReviewCommandController(
            VisitReviewCommandService commandService,
            MemberLifecycleService memberLifecycleService,
            IdempotencyService idempotencyService) {
        this.commandService = commandService;
        this.memberLifecycleService = memberLifecycleService;
        this.idempotencyService = idempotencyService;
    }

    @Operation(
            summary = "방문 후기 작성 (멱등성 보장)",
            description = "특정 장소에 방문 후기를 작성합니다. 멱등성 키(Idempotency-Key)를 지원하여 중복 제출을 방지합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "방문 후기 작성 성공"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 본문 내용 (글자 수 초과 또는 비어있음)", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "장소를 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/api/v1/places/{placeId}/visit-reviews")
    ResponseEntity<?> createReview(
            @Parameter(description = "장소 고유 식별자", example = "place-seoul-bukchon-001")
            @PathVariable String placeId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "방문 후기 내용", required = true)
            @RequestBody CreateReviewRequest body,
            @Parameter(description = "멱등성 보장 키 (UUID)", example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return memberLifecycleService.currentMember(sessionToken)
                .<ResponseEntity<?>>map(member -> createForMember(member.id(), placeId, body, idempotencyKey, request))
                .orElseGet(() -> authRequired(request));
    }

    @Operation(
            summary = "방문 후기 삭제",
            description = "작성자 본인의 방문 후기를 삭제합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "방문 후기 삭제 완료"),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "후기를 찾을 수 없거나 삭제 권한 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @DeleteMapping("/api/v1/visit-reviews/{reviewId}")
    ResponseEntity<?> deleteReview(
            @Parameter(description = "삭제할 후기 UUID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID reviewId,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return memberLifecycleService.currentMember(sessionToken)
                .<ResponseEntity<?>>map(member -> {
                    try {
                        commandService.delete(member.id(), reviewId);
                        return ResponseEntity.noContent()
                                .cacheControl(CacheControl.noStore())
                                .build();
                    } catch (VisitReviewNotFoundException exception) {
                        return notFound(request);
                    }
                })
                .orElseGet(() -> authRequired(request));
    }

    private ResponseEntity<?> createForMember(
            UUID memberId,
            String placeId,
            CreateReviewRequest body,
            String idempotencyKey,
            HttpServletRequest request) {
        try {
            var command = new CreateVisitReviewCommand(body == null ? null : body.text());
            var path = request.getRequestURI();
            var fingerprint = IdempotencyFingerprint.sha256(request.getMethod(), path, memberId.toString(), command);
            var response = idempotencyService.execute(new IdempotencyCommand(
                    IdempotencyKey.fromHeader(idempotencyKey).value(),
                    memberId.toString(),
                    request.getMethod(),
                    path,
                    fingerprint), () -> {
                var created = commandService.create(memberId, placeId, command);
                return IdempotentResponse.created("/api/v1/visit-reviews/" + created.id(), created);
            });
            return toResponse(response);
        } catch (VisitReviewTextInvalidException exception) {
            return validationError(request, "text");
        } catch (VisitReviewPlaceNotEligibleException exception) {
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

    @Schema(description = "방문 후기 작성 요청 DTO")
    record CreateReviewRequest(
            @Schema(description = "후기 본문 내용 (최대 1,000자)", example = "한옥의 고즈넉한 정취와 마당의 풍경이 정말 인상 깊었습니다.")
            String text) {
    }
}

package com.yrootlab.onmaru.web.review.like;

import com.yrootlab.onmaru.community.like.SelfVisitReviewLikeException;
import com.yrootlab.onmaru.community.like.VisitReviewLikeNotFoundException;
import com.yrootlab.onmaru.community.like.VisitReviewLikeService;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Tag(name = "03. 지도 & 방문 후기 (Map & Reviews)", description = "방문 후기 작성, 조회, 좋아요, 신고, 행정구역별 지도 통계 API")
@RestController
public final class VisitReviewLikeController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final VisitReviewLikeService likeService;
    private final MemberLifecycleService memberLifecycleService;

    VisitReviewLikeController(VisitReviewLikeService likeService, MemberLifecycleService memberLifecycleService) {
        this.likeService = likeService;
        this.memberLifecycleService = memberLifecycleService;
    }

    @Operation(
            summary = "방문 후기 좋아요 등록",
            description = "방문 후기에 좋아요를 등록합니다. (본인이 작성한 후기에는 좋아요 불가)"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "좋아요 등록 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "본인 작성 후기 좋아요 금지", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "후기를 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/api/v1/visit-reviews/{reviewId}/likes/me")
    ResponseEntity<?> like(
            @Parameter(description = "후기 고유 식별자 (UUID)", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID reviewId,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return memberLifecycleService.currentMember(sessionToken)
                .<ResponseEntity<?>>map(member -> {
                    try {
                        return ResponseEntity.ok()
                                .cacheControl(CacheControl.noStore())
                                .body(likeService.like(member.id(), reviewId));
                    } catch (SelfVisitReviewLikeException exception) {
                        return selfLikeForbidden(request);
                    } catch (VisitReviewLikeNotFoundException exception) {
                        return notFound(request);
                    }
                })
                .orElseGet(() -> authRequired(request));
    }

    @Operation(
            summary = "방문 후기 좋아요 취소",
            description = "이전에 등록한 방문 후기 좋아요를 취소합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "좋아요 취소 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "후기를 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @DeleteMapping("/api/v1/visit-reviews/{reviewId}/likes/me")
    ResponseEntity<?> unlike(
            @Parameter(description = "후기 고유 식별자 (UUID)", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID reviewId,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return memberLifecycleService.currentMember(sessionToken)
                .<ResponseEntity<?>>map(member -> {
                    try {
                        return ResponseEntity.ok()
                                .cacheControl(CacheControl.noStore())
                                .body(likeService.unlike(member.id(), reviewId));
                    } catch (SelfVisitReviewLikeException exception) {
                        return selfLikeForbidden(request);
                    } catch (VisitReviewLikeNotFoundException exception) {
                        return notFound(request);
                    }
                })
                .orElseGet(() -> authRequired(request));
    }

    private ResponseEntity<ApiErrorResponse> authRequired(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("1.2", "AUTH_REQUIRED", "Authentication is required.", requestId(request), Map.of()));
    }

    private ResponseEntity<ApiErrorResponse> selfLikeForbidden(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "SELF_LIKE_FORBIDDEN",
                        "Cannot like your own review.",
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
}

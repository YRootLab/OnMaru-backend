package com.yrootlab.onmaru.web.review.like;

import com.yrootlab.onmaru.community.like.SelfVisitReviewLikeException;
import com.yrootlab.onmaru.community.like.VisitReviewLikeNotFoundException;
import com.yrootlab.onmaru.community.like.VisitReviewLikeService;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
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

@RestController
public final class VisitReviewLikeController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final VisitReviewLikeService likeService;
    private final MemberLifecycleService memberLifecycleService;

    VisitReviewLikeController(VisitReviewLikeService likeService, MemberLifecycleService memberLifecycleService) {
        this.likeService = likeService;
        this.memberLifecycleService = memberLifecycleService;
    }

    @PutMapping("/api/v1/visit-reviews/{reviewId}/likes/me")
    ResponseEntity<?> like(
            @PathVariable UUID reviewId,
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

    @DeleteMapping("/api/v1/visit-reviews/{reviewId}/likes/me")
    ResponseEntity<?> unlike(
            @PathVariable UUID reviewId,
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

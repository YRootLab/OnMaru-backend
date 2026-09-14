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

    @PostMapping("/api/v1/places/{placeId}/visit-reviews")
    ResponseEntity<?> createReview(
            @PathVariable String placeId,
            @RequestBody CreateReviewRequest body,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return memberLifecycleService.currentMember(sessionToken)
                .<ResponseEntity<?>>map(member -> createForMember(member.id(), placeId, body, idempotencyKey, request))
                .orElseGet(() -> authRequired(request));
    }

    @DeleteMapping("/api/v1/visit-reviews/{reviewId}")
    ResponseEntity<?> deleteReview(
            @PathVariable UUID reviewId,
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

    record CreateReviewRequest(String text) {
    }
}

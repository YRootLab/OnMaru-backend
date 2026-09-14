package com.yrootlab.onmaru.web.review.query;

import com.yrootlab.onmaru.community.query.ReviewQueryScope;
import com.yrootlab.onmaru.community.query.VisitReviewCursorExpiredException;
import com.yrootlab.onmaru.community.query.VisitReviewCursorInvalidException;
import com.yrootlab.onmaru.community.query.VisitReviewInvalidRequestException;
import com.yrootlab.onmaru.community.query.VisitReviewQuery;
import com.yrootlab.onmaru.community.query.VisitReviewQueryService;
import com.yrootlab.onmaru.community.query.VisitReviewUnavailableException;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
public final class VisitReviewQueryController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final VisitReviewQueryService queryService;
    private final MemberLifecycleService memberLifecycleService;

    VisitReviewQueryController(VisitReviewQueryService queryService, MemberLifecycleService memberLifecycleService) {
        this.queryService = queryService;
        this.memberLifecycleService = memberLifecycleService;
    }

    @GetMapping("/api/v1/visit-reviews")
    ResponseEntity<?> listVisitReviews(
            @RequestParam String scope,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            var query = new VisitReviewQuery(
                    parseScope(scope),
                    normalize(regionCode),
                    null,
                    limit,
                    cursor,
                    memberId(sessionToken));
            return ok(query);
        } catch (VisitReviewInvalidRequestException exception) {
            return validationError(request, exception.field());
        } catch (VisitReviewCursorInvalidException exception) {
            return error(ApiErrorCode.CURSOR_INVALID, request, Map.of());
        } catch (VisitReviewCursorExpiredException exception) {
            return error(ApiErrorCode.CURSOR_EXPIRED, request, Map.of());
        } catch (VisitReviewUnavailableException exception) {
            return unavailable(request);
        }
    }

    @GetMapping("/api/v1/places/{placeId}/visit-reviews")
    ResponseEntity<?> listPlaceVisitReviews(
            @PathVariable String placeId,
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            return ok(VisitReviewQuery.place(placeId, limit, cursor, memberId(sessionToken)));
        } catch (VisitReviewInvalidRequestException exception) {
            return validationError(request, exception.field());
        } catch (VisitReviewCursorInvalidException exception) {
            return error(ApiErrorCode.CURSOR_INVALID, request, Map.of());
        } catch (VisitReviewCursorExpiredException exception) {
            return error(ApiErrorCode.CURSOR_EXPIRED, request, Map.of());
        } catch (VisitReviewUnavailableException exception) {
            return unavailable(request);
        }
    }

    private ResponseEntity<?> ok(VisitReviewQuery query) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(queryService.list(query));
    }

    private ReviewQueryScope parseScope(String scope) {
        try {
            return ReviewQueryScope.valueOf(scope);
        } catch (IllegalArgumentException exception) {
            throw new VisitReviewInvalidRequestException("scope");
        }
    }

    private Optional<UUID> memberId(String sessionToken) {
        return memberLifecycleService.currentMember(sessionToken).map(member -> member.id());
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ResponseEntity<ApiErrorResponse> validationError(HttpServletRequest request, String field) {
        return error(ApiErrorCode.VALIDATION_ERROR, request, Map.of("field", field));
    }

    private ResponseEntity<ApiErrorResponse> unavailable(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "SERVICE_UNAVAILABLE",
                        "Visit review data is temporarily unavailable.",
                        requestId(request),
                        Map.of("retryAfterMs", 30000)));
    }

    private ResponseEntity<ApiErrorResponse> error(
            ApiErrorCode code,
            HttpServletRequest request,
            Map<String, Object> details) {
        return ResponseEntity.status(code.status())
                .cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of(code, requestId(request), details));
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

package com.yrootlab.onmaru.web.audio;

import com.yrootlab.onmaru.audio.query.OdiiCursorExpiredException;
import com.yrootlab.onmaru.audio.query.OdiiCursorInvalidException;
import com.yrootlab.onmaru.audio.query.OdiiStoryInvalidRequestException;
import com.yrootlab.onmaru.audio.query.OdiiStoryNotFoundException;
import com.yrootlab.onmaru.audio.query.OdiiStoryQuery;
import com.yrootlab.onmaru.audio.query.OdiiStoryQueryService;
import com.yrootlab.onmaru.audio.query.OdiiStoryUnavailableException;
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
public final class OdiiStoryController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final OdiiStoryQueryService queryService;
    private final MemberLifecycleService memberLifecycleService;

    OdiiStoryController(OdiiStoryQueryService queryService, MemberLifecycleService memberLifecycleService) {
        this.queryService = queryService;
        this.memberLifecycleService = memberLifecycleService;
    }

    @GetMapping("/api/v1/odii/stories")
    ResponseEntity<?> listStories(
            @RequestParam(required = false, defaultValue = "ko-KR") String language,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false, defaultValue = "20") String limit,
            @RequestParam(required = false) String cursor,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            return ok(queryService.list(new OdiiStoryQuery(
                    language,
                    category,
                    regionCode,
                    parseLimit(limit),
                    cursor,
                    memberId(sessionToken))));
        } catch (OdiiStoryInvalidRequestException exception) {
            return invalidRequest(request, exception.field());
        } catch (OdiiCursorInvalidException exception) {
            return error(ApiErrorCode.CURSOR_INVALID, request, Map.of());
        } catch (OdiiCursorExpiredException exception) {
            return error(ApiErrorCode.CURSOR_EXPIRED, request, Map.of());
        } catch (OdiiStoryUnavailableException exception) {
            return unavailable(request);
        }
    }

    @GetMapping("/api/v1/odii/stories/{storyId}")
    ResponseEntity<?> storyDetail(
            @PathVariable String storyId,
            @RequestParam(required = false, defaultValue = "ko-KR") String language,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            return ok(queryService.detail(storyId, language, memberId(sessionToken)));
        } catch (OdiiStoryInvalidRequestException exception) {
            return invalidRequest(request, exception.field());
        } catch (OdiiStoryNotFoundException exception) {
            return notFound(request);
        } catch (OdiiStoryUnavailableException exception) {
            return unavailable(request);
        }
    }

    private ResponseEntity<Object> ok(Object body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private Optional<UUID> memberId(String sessionToken) {
        return memberLifecycleService.currentMember(sessionToken).map(member -> member.id());
    }

    private int parseLimit(String limit) {
        try {
            return Integer.parseInt(limit);
        } catch (NumberFormatException exception) {
            throw new OdiiStoryInvalidRequestException("limit");
        }
    }

    private ResponseEntity<ApiErrorResponse> invalidRequest(HttpServletRequest request, String field) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "INVALID_REQUEST",
                        "The requested Odii story query is invalid.",
                        requestId(request),
                        Map.of("field", field)));
    }

    private ResponseEntity<ApiErrorResponse> unavailable(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "SERVICE_UNAVAILABLE",
                        "Odii data is temporarily unavailable.",
                        requestId(request),
                        Map.of("retryAfterMs", 30000)));
    }

    private ResponseEntity<ApiErrorResponse> notFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "NOT_FOUND",
                        "The requested Odii story is not available.",
                        requestId(request),
                        Map.of("resourceType", "ODII_STORY")));
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

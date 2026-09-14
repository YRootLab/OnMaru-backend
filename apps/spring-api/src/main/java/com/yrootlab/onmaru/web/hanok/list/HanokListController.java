package com.yrootlab.onmaru.web.hanok.list;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokCursorExpiredException;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokCursorInvalidException;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListCategory;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListQuery;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListQueryService;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListUnavailableException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
public final class HanokListController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final HanokListQueryService queryService;
    private final MemberLifecycleService memberLifecycleService;

    HanokListController(HanokListQueryService queryService, MemberLifecycleService memberLifecycleService) {
        this.queryService = queryService;
        this.memberLifecycleService = memberLifecycleService;
    }

    @GetMapping("/api/v1/hanoks")
    ResponseEntity<?> listHanoks(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String category,
            @RequestParam(required = false, defaultValue = "false") boolean hasImage,
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            var query = new HanokListQuery(
                    keyword,
                    regionCode,
                    parseCategory(category),
                    hasImage,
                    Math.min(Math.max(limit, 1), 50),
                    cursor,
                    memberId(sessionToken));
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(queryService.list(query));
        } catch (HanokCursorInvalidException exception) {
            return error(ApiErrorCode.CURSOR_INVALID, request, Map.of());
        } catch (HanokCursorExpiredException exception) {
            return error(ApiErrorCode.CURSOR_EXPIRED, request, Map.of());
        } catch (HanokListUnavailableException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiErrorResponse(
                            "1.2",
                            "SERVICE_UNAVAILABLE",
                            "Catalog data is temporarily unavailable.",
                            requestId(request),
                            Map.of("retryAfterMs", 30000)));
        }
    }

    private HanokListCategory parseCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        try {
            return HanokListCategory.valueOf(category);
        } catch (IllegalArgumentException exception) {
            throw new HanokCursorInvalidException();
        }
    }

    private Optional<UUID> memberId(String sessionToken) {
        return memberLifecycleService.currentMember(sessionToken).map(member -> member.id());
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

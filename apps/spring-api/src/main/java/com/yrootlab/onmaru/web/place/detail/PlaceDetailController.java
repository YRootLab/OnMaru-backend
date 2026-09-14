package com.yrootlab.onmaru.web.place.detail;

import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailQueryService;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailUnavailableException;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
public final class PlaceDetailController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final PlaceDetailQueryService queryService;
    private final MemberLifecycleService memberLifecycleService;

    PlaceDetailController(PlaceDetailQueryService queryService, MemberLifecycleService memberLifecycleService) {
        this.queryService = queryService;
        this.memberLifecycleService = memberLifecycleService;
    }

    @GetMapping("/api/v1/places/{placeId}")
    ResponseEntity<?> canonicalPlace(
            @PathVariable String placeId,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            return queryService.findCanonicalPlace(placeId, memberId(sessionToken))
                    .<ResponseEntity<?>>map(detail -> ResponseEntity.ok()
                            .cacheControl(CacheControl.noStore())
                            .body(detail))
                    .orElseGet(() -> placeNotFound(request));
        } catch (PlaceDetailUnavailableException exception) {
            return serviceUnavailable(request);
        }
    }

    @GetMapping("/api/v1/hanoks/{placeId}")
    ResponseEntity<?> hanok(
            @PathVariable String placeId,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            return queryService.findHanok(placeId, memberId(sessionToken))
                    .<ResponseEntity<?>>map(detail -> ResponseEntity.ok()
                            .cacheControl(CacheControl.noStore())
                            .body(detail))
                    .orElseGet(() -> placeNotFound(request));
        } catch (PlaceDetailUnavailableException exception) {
            return serviceUnavailable(request);
        }
    }

    private Optional<UUID> memberId(String sessionToken) {
        return memberLifecycleService.currentMember(sessionToken).map(member -> member.id());
    }

    private ResponseEntity<ApiErrorResponse> placeNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "NOT_FOUND",
                        "The requested place is not available.",
                        requestId(request),
                        Map.of("resourceType", "PLACE")));
    }

    private ResponseEntity<ApiErrorResponse> serviceUnavailable(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "SERVICE_UNAVAILABLE",
                        "Catalog data is temporarily unavailable.",
                        requestId(request),
                        Map.of("retryAfterMs", 30000)));
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

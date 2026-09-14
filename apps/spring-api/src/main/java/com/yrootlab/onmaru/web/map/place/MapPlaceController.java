package com.yrootlab.onmaru.web.map.place;

import com.yrootlab.onmaru.catalog.application.query.spatial.MapBoundingBox;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceInvalidRequestException;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceQuery;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceQueryService;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceUnavailableException;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
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
public final class MapPlaceController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final MapPlaceQueryService queryService;
    private final MemberLifecycleService memberLifecycleService;

    MapPlaceController(MapPlaceQueryService queryService, MemberLifecycleService memberLifecycleService) {
        this.queryService = queryService;
        this.memberLifecycleService = memberLifecycleService;
    }

    @GetMapping("/api/v1/map/places")
    ResponseEntity<?> listMapPlaces(
            @RequestParam(required = false, defaultValue = "ko-KR") String language,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String bbox,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Integer radius,
            @RequestParam(required = false) String category,
            @RequestParam(required = false, defaultValue = "20") int limit,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            var query = new MapPlaceQuery(
                    language,
                    normalize(regionCode),
                    parseBbox(bbox),
                    lat,
                    lng,
                    radius,
                    normalize(category),
                    limit,
                    memberId(sessionToken));
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(queryService.list(query));
        } catch (MapPlaceInvalidRequestException exception) {
            return invalidRequest(request, exception.field());
        } catch (MapPlaceUnavailableException exception) {
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

    private MapBoundingBox parseBbox(String bbox) {
        if (bbox == null || bbox.isBlank()) {
            return null;
        }
        var parts = bbox.split(",", -1);
        if (parts.length != 4) {
            throw new MapPlaceInvalidRequestException("bbox");
        }
        try {
            return new MapBoundingBox(
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]));
        } catch (NumberFormatException exception) {
            throw new MapPlaceInvalidRequestException("bbox");
        }
    }

    private Optional<UUID> memberId(String sessionToken) {
        return memberLifecycleService.currentMember(sessionToken).map(member -> member.id());
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ResponseEntity<ApiErrorResponse> invalidRequest(HttpServletRequest request, String field) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "INVALID_REQUEST",
                        "The requested map bounds are invalid.",
                        requestId(request),
                        Map.of("field", field)));
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

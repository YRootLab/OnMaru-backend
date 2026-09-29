package com.yrootlab.onmaru.web.map.viewport;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoBounds;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoCategory;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportInvalidRequestException;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportQuery;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportQueryService;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
public final class MapInfoViewportController {

    private final MapInfoViewportQueryService service;

    public MapInfoViewportController(MapInfoViewportQueryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/map/info/viewport")
    ResponseEntity<?> viewport(
            @RequestParam String bbox,
            @RequestParam int zoomLevel,
            @RequestParam(required = false, defaultValue = "ALL") String category,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(required = false, defaultValue = "ko-KR") String language,
            @RequestParam(required = false, defaultValue = "500") int limit,
            HttpServletRequest request) {
        try {
            var query = new MapInfoViewportQuery(
                    parseBbox(bbox), zoomLevel, parseCategory(category), normalize(regionCode),
                    normalize(snapshotId), language, limit);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.find(query));
        } catch (MapInfoViewportInvalidRequestException exception) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(new ApiErrorResponse(
                    "1.0", "INVALID_REQUEST", "The requested map viewport is invalid.", requestId(request),
                    Map.of("field", exception.field())));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(new ApiErrorResponse(
                    "1.0", "CATALOG_UNAVAILABLE", "Map catalog data is temporarily unavailable.", requestId(request),
                    Map.of()));
        }
    }

    private MapInfoBounds parseBbox(String value) {
        if (value == null || value.isBlank()) throw new MapInfoViewportInvalidRequestException("bbox");
        var parts = value.split(",", -1);
        if (parts.length != 4) throw new MapInfoViewportInvalidRequestException("bbox");
        try {
            return new MapInfoBounds(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
        } catch (NumberFormatException exception) {
            throw new MapInfoViewportInvalidRequestException("bbox");
        }
    }

    private MapInfoCategory parseCategory(String value) {
        try { return MapInfoCategory.valueOf(value.trim().toUpperCase()); }
        catch (Exception exception) { throw new MapInfoViewportInvalidRequestException("category"); }
    }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private String requestId(HttpServletRequest request) {
        var attribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (attribute instanceof String id && !id.isBlank()) return id;
        var header = request.getHeader(RequestIdFilter.HEADER);
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header;
    }
}

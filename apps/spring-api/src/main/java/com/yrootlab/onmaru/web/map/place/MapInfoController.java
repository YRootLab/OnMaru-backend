package com.yrootlab.onmaru.web.map.place;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.*;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
public final class MapInfoController {
    private final MapInfoQueryService service;

    public MapInfoController(MapInfoQueryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/map/info/places")
    ResponseEntity<?> list(
            @RequestParam(required = false, defaultValue = "ALL") String category,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String bbox,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(required = false, defaultValue = "ko-KR") String language,
            @RequestParam(required = false, defaultValue = "30") int limit,
            @RequestParam(required = false, defaultValue = "REGION_NAME") String sort,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            HttpServletRequest request) {
        try {
            var query = new MapInfoListQuery(
                    MapInfoCategory.valueOf(category.trim().toUpperCase()),
                    clean(regionCode), parseBbox(bbox), clean(cursor), clean(snapshotId),
                    language, limit, sort, lat, lng);
            return ResponseEntity.ok(service.list(query));
        } catch (MapInfoQueryException | IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(
                    "1.0", "INVALID_REQUEST", "The requested map list is invalid.",
                    requestId(request), Map.of("field", exception instanceof MapInfoQueryException q ? q.field() : "query")));
        } catch (IllegalStateException exception) {
            return ResponseEntity.internalServerError().body(new ApiErrorResponse(
                    "1.0", "CATALOG_UNAVAILABLE", "Map catalog data is temporarily unavailable.",
                    requestId(request), Map.of()));
        }
    }

    private MapInfoBounds parseBbox(String value) {
        if (value == null || value.isBlank()) return null;
        var p = value.split(",", -1);
        if (p.length != 4) throw new MapInfoQueryException("INVALID_REQUEST", "bbox");
        try {
            return new MapInfoBounds(Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                    Double.parseDouble(p[2]), Double.parseDouble(p[3]));
        } catch (NumberFormatException e) {
            throw new MapInfoQueryException("INVALID_REQUEST", "bbox");
        }
    }

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private String requestId(HttpServletRequest request) {
        var attribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (attribute instanceof String id && !id.isBlank()) return id;
        var header = request.getHeader(RequestIdFilter.HEADER);
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header;
    }
}

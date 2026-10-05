package com.yrootlab.onmaru.web.map.place;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.*;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import com.yrootlab.onmaru.web.map.MapInfoRequestExecutor;
import com.yrootlab.onmaru.web.map.MapInfoRequestTimeoutException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;
import java.time.Duration;

@RestController
public final class MapInfoController {
    private final MapInfoQueryService service;
    private final MeterRegistry meterRegistry;
    private final MapInfoRequestExecutor requestExecutor;

    public MapInfoController(MapInfoQueryService service) {
        this(service, (MeterRegistry) null, new MapInfoRequestExecutor(Duration.ofSeconds(2)));
    }

    @Autowired
    MapInfoController(MapInfoQueryService service, ObjectProvider<MeterRegistry> meterRegistryProvider,
                      MapInfoRequestExecutor requestExecutor) {
        this(service, meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable(), requestExecutor);
    }

    MapInfoController(MapInfoQueryService service, MeterRegistry meterRegistry, MapInfoRequestExecutor requestExecutor) {
        this.service = service;
        this.meterRegistry = meterRegistry;
        this.requestExecutor = requestExecutor;
    }

    @GetMapping("/api/v1/map/info/places")
    ResponseEntity<?> list(
            @RequestParam(required = false) String category,
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
            long started = System.nanoTime();
            var query = new MapInfoListQuery(
                    parseCategory(category),
                    clean(regionCode), parseBbox(bbox), clean(cursor), clean(snapshotId),
                    language, limit, sort, lat, lng);
            var response = ResponseEntity.ok(requestExecutor.execute(() -> service.list(query)));
            record("success", category, System.nanoTime() - started);
            return response;
        } catch (MapInfoRequestTimeoutException exception) {
            record("timeout", category, 0);
            return ResponseEntity.status(503).body(new ApiErrorResponse(
                    "1.0", "CATALOG_UNAVAILABLE", "Map catalog data is temporarily unavailable.",
                    requestId(request), Map.of("timeout", true)));
        } catch (MapInfoQueryException exception) {
            if ("SNAPSHOT_EXPIRED".equals(exception.code())) {
                record("snapshot_expired", category, 0);
                return ResponseEntity.status(409).body(new ApiErrorResponse(
                        "1.0", "SNAPSHOT_EXPIRED", "The requested map snapshot is no longer available.",
                        requestId(request), Map.of("field", exception.field())));
            }
            record("invalid", category, 0);
            return ResponseEntity.badRequest().body(new ApiErrorResponse(
                    "1.0", "INVALID_REQUEST", "The requested map list is invalid.",
                    requestId(request), Map.of("field", exception.field())));
        } catch (IllegalArgumentException exception) {
            record("invalid", category, 0);
            return ResponseEntity.badRequest().body(new ApiErrorResponse(
                    "1.0", "INVALID_REQUEST", "The requested map list is invalid.",
                    requestId(request), Map.of("field", "query")));
        } catch (IllegalStateException exception) {
            record("unavailable", category, 0);
            return ResponseEntity.status(503).body(new ApiErrorResponse(
                    "1.0", "CATALOG_UNAVAILABLE", "Map catalog data is temporarily unavailable.",
                    requestId(request), Map.of()));
        }
    }

    private void record(String outcome, String category, long elapsedNanos) {
        if (meterRegistry == null) return;
        var tags = new String[] {"endpoint", "places", "outcome", outcome, "category", category == null ? "unknown" : category};
        meterRegistry.counter("onmaru.map.info.requests", tags).increment();
        if ("timeout".equals(outcome)) meterRegistry.counter("onmaru.map.info.query.timeout", "endpoint", "places").increment();
        if (elapsedNanos > 0) meterRegistry.timer("onmaru.map.info.query.duration", tags).record(elapsedNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
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

    private MapInfoCategory parseCategory(String value) {
        if (value == null || value.isBlank()) throw new MapInfoQueryException("INVALID_REQUEST", "category");
        try {
            var parsed = MapInfoCategory.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            if (parsed == MapInfoCategory.ALL) throw new MapInfoQueryException("INVALID_REQUEST", "category");
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new MapInfoQueryException("INVALID_REQUEST", "category");
        }
    }

    private String requestId(HttpServletRequest request) {
        var attribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (attribute instanceof String id && !id.isBlank()) return id;
        var header = request.getHeader(RequestIdFilter.HEADER);
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header;
    }
}

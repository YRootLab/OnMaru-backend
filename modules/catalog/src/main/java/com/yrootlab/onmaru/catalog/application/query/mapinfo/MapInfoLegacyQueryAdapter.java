package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import com.yrootlab.onmaru.catalog.application.query.spatial.MapCoordinates;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapCoverageStatus;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapDataAvailability;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceCard;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceInvalidRequestException;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlacePage;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceQuery;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapRegionRef;

import java.util.List;

/**
 * Compatibility boundary for the former /map/places response.
 * Production wiring uses MapInfoQueryService, so the legacy endpoint no longer
 * materializes the whole catalog before filtering it in Java.
 */
public final class MapInfoLegacyQueryAdapter {
    private static final int MAX_LEGACY_LIMIT = 100;
    private final MapInfoQueryService queryService;

    public MapInfoLegacyQueryAdapter(MapInfoQueryService queryService) {
        this.queryService = queryService;
    }

    public MapPlacePage list(MapPlaceQuery query) {
        var category = category(query.category());
        var bounds = bounds(query);
        var sort = query.lat() == null ? "REGION_NAME" : "DISTANCE";
        var result = queryService.list(new MapInfoListQuery(
                category,
                query.regionCode(),
                bounds,
                null,
                null,
                query.language(),
                Math.min(query.limit(), MAX_LEGACY_LIMIT),
                sort,
                query.lat(),
                query.lng()));
        var cards = result.items().stream().map(this::card).toList();
        return new MapPlacePage(
                "1.2",
                result.coverage().equals("COMPLETE") ? MapCoverageStatus.COMPLETE : MapCoverageStatus.PARTIAL,
                result.query().language(),
                cards,
                result.nextCursor(),
                result.nextCursor() != null);
    }

    private MapPlaceCard card(MapInfoPlaceItem item) {
        return new MapPlaceCard(
                item.placeId(),
                item.name(),
                item.displayCategory(),
                new MapRegionRef(item.region().regionCode(), item.region().name(), "UNKNOWN", null),
                new MapCoordinates(item.coordinates().lat(), item.coordinates().lng()),
                item.thumbnailUrl(),
                item.summary(),
                item.savedByMe(),
                List.of(),
                new MapDataAvailability(MapCoverageStatus.COMPLETE, MapCoverageStatus.MISSING, MapCoverageStatus.MISSING));
    }

    private MapInfoCategory category(String value) {
        if (value == null || value.isBlank()) return MapInfoCategory.ALL;
        var normalized = value.trim().toUpperCase();
        if (normalized.equals("HANOK") || normalized.equals("TOURIST") || normalized.equals("RESTAURANT")) {
            return normalized.equals("RESTAURANT") ? MapInfoCategory.FOOD : MapInfoCategory.SPOT;
        }
        try {
            return MapInfoCategory.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new MapPlaceInvalidRequestException("category");
        }
    }

    private MapInfoBounds bounds(MapPlaceQuery query) {
        if (query.bbox() != null) {
            return new MapInfoBounds(query.bbox().west(), query.bbox().south(), query.bbox().east(), query.bbox().north());
        }
        if (query.lat() == null || query.radiusMeters() == null) return null;
        double latDelta = query.radiusMeters() / 111_320d;
        double lngDelta = query.radiusMeters() / (111_320d * Math.max(0.1d, Math.cos(Math.toRadians(query.lat()))));
        return new MapInfoBounds(query.lng() - lngDelta, query.lat() - latDelta,
                query.lng() + lngDelta, query.lat() + latDelta);
    }
}

package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.util.List;
import java.util.Map;

public record MapInfoViewportItem(
        String type,
        String id,
        String name,
        MapInfoPoint center,
        MapInfoBounds bounds,
        long count,
        Map<String, Long> categoryCounts,
        int targetZoomLevel,
        String placeId,
        String displayCategory,
        String regionCode) {

    public MapInfoViewportItem {
        categoryCounts = Map.copyOf(categoryCounts);
    }
}

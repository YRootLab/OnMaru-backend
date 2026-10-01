package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.util.List;

public record MapInfoViewportResponse(
        String schemaVersion,
        MapInfoRenderMode renderMode,
        String profileVersion,
        MapInfoSnapshot snapshot,
        long totalCountInViewport,
        List<MapInfoViewportItem> items,
        List<String> appliedCategories,
        String coverage,
        MapInfoBounds servedBbox,
        MapInfoProjectionPublication projection) {

    public MapInfoViewportResponse {
        items = List.copyOf(items);
        appliedCategories = List.copyOf(appliedCategories);
    }

    public MapInfoViewportResponse asStale() {
        return new MapInfoViewportResponse(schemaVersion, renderMode, profileVersion, snapshot,
                totalCountInViewport, items, appliedCategories, "STALE", servedBbox, projection);
    }
}

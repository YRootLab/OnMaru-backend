package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.util.List;

public record MapInfoQueryResult(
        MapInfoSnapshot snapshot,
        MapInfoProjectionPublication publication,
        long totalCount,
        List<MapInfoPlaceItem> items,
        MapInfoCursorPosition lastCursor,
        boolean hasMore) {
    public MapInfoQueryResult {
        items = List.copyOf(items);
    }
}

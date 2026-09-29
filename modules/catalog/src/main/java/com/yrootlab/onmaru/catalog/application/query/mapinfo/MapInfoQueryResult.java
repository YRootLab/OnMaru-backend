package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.util.List;

public record MapInfoQueryResult(
        MapInfoSnapshot snapshot,
        MapInfoProjectionPublication publication,
        long totalCount,
        List<MapInfoPlaceItem> items,
        MapInfoCursorPosition lastCursor,
        boolean hasMore,
        String coverage) {
    public MapInfoQueryResult(MapInfoSnapshot snapshot, MapInfoProjectionPublication publication, long totalCount,
                              List<MapInfoPlaceItem> items, MapInfoCursorPosition lastCursor, boolean hasMore) {
        this(snapshot, publication, totalCount, items, lastCursor, hasMore, hasMore ? "PARTIAL" : "COMPLETE");
    }

    public MapInfoQueryResult {
        items = List.copyOf(items);
    }

    public MapInfoQueryResult asStale() {
        return new MapInfoQueryResult(snapshot, publication, totalCount, items, lastCursor, hasMore, "STALE");
    }
}

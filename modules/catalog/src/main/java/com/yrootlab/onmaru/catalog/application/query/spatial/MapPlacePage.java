package com.yrootlab.onmaru.catalog.application.query.spatial;

import java.util.List;

public record MapPlacePage(
        String schemaVersion,
        MapCoverageStatus coverageStatus,
        String language,
        List<MapPlaceCard> items,
        String nextCursor,
        boolean hasMore) {

    public MapPlacePage {
        items = List.copyOf(items);
    }
}

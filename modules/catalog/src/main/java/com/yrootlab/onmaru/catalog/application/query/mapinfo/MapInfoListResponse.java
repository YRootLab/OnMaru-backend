package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.util.List;

public record MapInfoListResponse(
        String schemaVersion,
        MapInfoListQuery query,
        MapInfoSnapshot snapshot,
        long totalCount,
        List<MapInfoPlaceItem> items,
        String nextCursor,
        List<String> appliedCategories,
        String coverage,
        MapInfoProjectionPublication projection) {

    public MapInfoListResponse {
        items = List.copyOf(items);
        appliedCategories = List.copyOf(appliedCategories);
    }
}

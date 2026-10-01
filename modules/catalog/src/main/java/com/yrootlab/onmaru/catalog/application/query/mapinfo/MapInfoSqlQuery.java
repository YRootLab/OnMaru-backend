package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.util.List;

public record MapInfoSqlQuery(
        String snapshotId,
        List<String> canonicalCategories,
        String regionCode,
        MapInfoBounds bbox,
        String sort,
        Double lat,
        Double lng,
        int limit,
        MapInfoCursorPosition cursor,
        String legacyCategory) {
    public MapInfoSqlQuery {
        canonicalCategories = List.copyOf(canonicalCategories == null ? List.of() : canonicalCategories);
    }
}

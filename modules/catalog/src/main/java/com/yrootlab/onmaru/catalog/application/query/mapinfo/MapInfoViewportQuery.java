package com.yrootlab.onmaru.catalog.application.query.mapinfo;

public record MapInfoViewportQuery(
        MapInfoBounds bbox,
        int zoomLevel,
        MapInfoCategory category,
        String regionCode,
        String snapshotId,
        String language,
        int limit) {

    public MapInfoViewportQuery {
        category = category == null ? MapInfoCategory.ALL : category;
        language = language == null || language.isBlank() ? "ko-KR" : language;
        limit = limit == 0 ? 500 : limit;
    }
}

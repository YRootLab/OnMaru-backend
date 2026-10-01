package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.util.List;

public record MapInfoListQuery(
        MapInfoCategory category,
        String regionCode,
        MapInfoBounds bbox,
        String cursor,
        String snapshotId,
        String language,
        int limit,
        String sort,
        Double lat,
        Double lng) {

    public MapInfoListQuery {
        category = category == null ? MapInfoCategory.ALL : category;
        language = language == null || language.isBlank() ? "ko-KR" : language;
        limit = limit == 0 ? 30 : limit;
    }
}

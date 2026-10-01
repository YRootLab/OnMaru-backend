package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.util.List;

public record MapInfoPlaceItem(
        String placeId,
        String name,
        String displayCategory,
        List<String> matchedCategories,
        MapInfoRegionRef region,
        MapInfoPoint coordinates,
        String thumbnailUrl,
        String summary,
        boolean savedByMe) {

    public MapInfoPlaceItem {
        matchedCategories = List.copyOf(matchedCategories);
    }
}

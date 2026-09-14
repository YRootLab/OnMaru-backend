package com.yrootlab.onmaru.catalog.application.query.spatial;

import java.util.List;

public record MapPlaceCard(
        String placeId,
        String name,
        String category,
        MapRegionRef region,
        MapCoordinates coordinates,
        String thumbnailUrl,
        String summary,
        boolean savedByMe,
        List<String> linkedOdiiStoryIds,
        MapDataAvailability dataAvailability) {

    public MapPlaceCard {
        linkedOdiiStoryIds = List.copyOf(linkedOdiiStoryIds);
    }
}

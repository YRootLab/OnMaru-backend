package com.yrootlab.onmaru.catalog.application.query.spatial;

import java.util.List;

public record MapPlaceProjection(
        String placeId,
        String name,
        String category,
        MapRegionRef region,
        MapCoordinates coordinates,
        String thumbnailUrl,
        String summary,
        List<String> linkedOdiiStoryIds,
        MapDataAvailability dataAvailability,
        MapPlaceStatus status) {

    public MapPlaceProjection {
        linkedOdiiStoryIds = List.copyOf(linkedOdiiStoryIds);
    }

    public MapPlaceProjection hidden() {
        return new MapPlaceProjection(
                placeId,
                name,
                category,
                region,
                coordinates,
                thumbnailUrl,
                summary,
                linkedOdiiStoryIds,
                dataAvailability,
                MapPlaceStatus.HIDDEN);
    }
}

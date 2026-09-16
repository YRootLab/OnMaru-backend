package com.yrootlab.onmaru.audio.query;

public record OdiiStorySummary(
        String storyId,
        String title,
        String audioTitle,
        String category,
        OdiiRegionRef region,
        OdiiCoordinates coordinates,
        Integer durationSeconds,
        String imageUrl,
        String linkedPlaceId,
        boolean savedByMe) {
}

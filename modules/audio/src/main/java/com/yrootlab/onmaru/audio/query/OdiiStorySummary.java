package com.yrootlab.onmaru.audio.query;

import java.util.List;

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
        List<String> contentTags,
        boolean savedByMe) {

    public OdiiStorySummary {
        contentTags = contentTags == null ? List.of() : List.copyOf(contentTags);
    }
}

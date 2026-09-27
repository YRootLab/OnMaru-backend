package com.yrootlab.onmaru.catalog.application.query.hanok;

import com.yrootlab.onmaru.catalog.application.query.spatial.MapCoordinates;

import java.util.List;

public record HanokCard(
        String placeId,
        String name,
        HanokListCategory category,
        String regionName,
        String address,
        MapCoordinates coordinates,
        String thumbnailUrl,
        String summary,
        List<String> tags,
        boolean savedByMe) {

    public HanokCard(
            String placeId,
            String name,
            HanokListCategory category,
            String regionName,
            String thumbnailUrl,
            String summary,
            List<String> tags,
            boolean savedByMe) {
        this(placeId, name, category, regionName, null, null,
                thumbnailUrl, summary, tags, savedByMe);
    }
}

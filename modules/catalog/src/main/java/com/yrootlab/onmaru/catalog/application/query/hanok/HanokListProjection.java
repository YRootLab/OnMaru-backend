package com.yrootlab.onmaru.catalog.application.query.hanok;

import com.yrootlab.onmaru.catalog.application.query.spatial.MapCoordinates;

import java.time.Instant;
import java.util.List;

public record HanokListProjection(
        String placeId,
        String name,
        HanokListCategory category,
        String regionCode,
        String regionName,
        String address,
        MapCoordinates coordinates,
        String thumbnailUrl,
        String summary,
        List<String> tags,
        Instant publishedAt,
        HanokListStatus status) {

    public HanokListProjection {
        tags = List.copyOf(tags);
    }

    public HanokListProjection(
            String placeId,
            String name,
            HanokListCategory category,
            String regionCode,
            String regionName,
            String thumbnailUrl,
            String summary,
            List<String> tags,
            Instant publishedAt,
            HanokListStatus status) {
        this(placeId, name, category, regionCode, regionName, null, null,
                thumbnailUrl, summary, tags, publishedAt, status);
    }

    public HanokListProjection hidden() {
        return new HanokListProjection(
                placeId,
                name,
                category,
                regionCode,
                regionName,
                address,
                coordinates,
                thumbnailUrl,
                summary,
                tags,
                publishedAt,
                HanokListStatus.HIDDEN);
    }
}

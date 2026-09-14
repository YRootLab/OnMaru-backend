package com.yrootlab.onmaru.catalog.application.query.hanok;

import java.time.Instant;
import java.util.List;

public record HanokListProjection(
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

    public HanokListProjection {
        tags = List.copyOf(tags);
    }

    public HanokListProjection hidden() {
        return new HanokListProjection(
                placeId,
                name,
                category,
                regionCode,
                regionName,
                thumbnailUrl,
                summary,
                tags,
                publishedAt,
                HanokListStatus.HIDDEN);
    }
}

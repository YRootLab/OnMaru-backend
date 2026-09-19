package com.yrootlab.onmaru.catalog.application.query.hanok;

import java.util.List;

public record HanokCard(
        String placeId,
        String name,
        HanokListCategory category,
        String regionName,
        String thumbnailUrl,
        String summary,
        List<String> tags,
        boolean savedByMe) {
}

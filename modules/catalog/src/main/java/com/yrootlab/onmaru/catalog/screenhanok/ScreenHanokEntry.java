package com.yrootlab.onmaru.catalog.screenhanok;

import java.util.List;

public record ScreenHanokEntry(
        String placeId,
        String name,
        String regionName,
        String imageUrl,
        ScreenHanokMediaType mediaType,
        String workTitle,
        String subtitle,
        List<String> tags,
        String sourceUrl,
        String sourceTitle,
        boolean savedByMe) {
}

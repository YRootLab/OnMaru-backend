package com.yrootlab.onmaru.catalog.screenhanok;

import java.time.Instant;
import java.util.List;

public record ScreenHanokPlacement(
        String placeId,
        ScreenHanokMediaType mediaType,
        String workTitle,
        String subtitle,
        List<String> tags,
        String sourceUrl,
        String sourceTitle,
        Instant publishedAt) {

    public ScreenHanokPlacement {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new IllegalArgumentException("sourceUrl must not be blank (ADR-0010: unsourced matches are never published)");
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}

package com.yrootlab.onmaru.catalog.screenhanok;

import java.util.List;

public record ScreenHanokMatch(
        String placeId,
        ScreenHanokMediaType mediaType,
        String workTitle,
        String subtitle,
        List<String> tags,
        String sourceUrl,
        String sourceTitle) {

    public ScreenHanokMatch {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public boolean hasSource() {
        return sourceUrl != null && !sourceUrl.isBlank();
    }
}

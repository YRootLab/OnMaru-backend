package com.yrootlab.onmaru.audio.query;

import java.util.List;
import java.util.UUID;

public record OdiiStoryReadPage(
        UUID revisionId,
        String language,
        OdiiLanguageStatus languageStatus,
        List<OdiiStoryProjection> stories,
        long totalCount,
        boolean hasMore) {

    public OdiiStoryReadPage {
        stories = stories == null ? List.of() : List.copyOf(stories);
    }
}

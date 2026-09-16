package com.yrootlab.onmaru.audio.query;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OdiiStoryCursor(
        UUID revisionId,
        String language,
        String category,
        String regionCode,
        int limit,
        Instant publishedAt,
        String storyId) {

    public OdiiStoryCursor {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (storyId == null || storyId.isBlank()) {
            throw new IllegalArgumentException("storyId must not be blank");
        }
    }

    boolean matches(OdiiStoryQuery query) {
        return language.equals(query.language())
                && Objects.equals(category, query.category())
                && Objects.equals(regionCode, query.regionCode())
                && limit == query.limit();
    }
}

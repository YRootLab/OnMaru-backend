package com.yrootlab.onmaru.audio.query;

import java.util.Optional;
import java.util.UUID;

public record OdiiStoryQuery(
        String language,
        String category,
        String regionCode,
        int limit,
        String cursor,
        Optional<UUID> memberId) {

    public OdiiStoryQuery {
        language = language == null || language.isBlank() ? "ko-KR" : language.trim();
        category = normalize(category);
        regionCode = normalize(regionCode);
        cursor = normalize(cursor);
        memberId = memberId == null ? Optional.empty() : memberId;
    }

    public static OdiiStoryQuery firstPage(String language, int limit, Optional<UUID> memberId) {
        return new OdiiStoryQuery(language, null, null, limit, null, memberId);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

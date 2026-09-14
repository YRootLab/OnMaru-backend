package com.yrootlab.onmaru.catalog.application.query.spatial;

import java.util.Optional;
import java.util.UUID;

public record MapPlaceQuery(
        String language,
        String regionCode,
        MapBoundingBox bbox,
        Double lat,
        Double lng,
        Integer radiusMeters,
        String category,
        int limit,
        Optional<UUID> memberId) {

    public MapPlaceQuery {
        language = language == null || language.isBlank() ? "ko-KR" : language;
        memberId = memberId == null ? Optional.empty() : memberId;
    }

    public static MapPlaceQuery nearby(
            double lat,
            double lng,
            int radiusMeters,
            String category,
            String language,
            int limit,
            Optional<UUID> memberId) {
        return new MapPlaceQuery(language, null, null, lat, lng, radiusMeters, category, limit, memberId);
    }

    public static MapPlaceQuery region(String regionCode, String language, int limit, Optional<UUID> memberId) {
        return new MapPlaceQuery(language, regionCode, null, null, null, null, null, limit, memberId);
    }
}

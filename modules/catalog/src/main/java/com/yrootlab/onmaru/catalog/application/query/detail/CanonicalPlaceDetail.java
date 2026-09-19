package com.yrootlab.onmaru.catalog.application.query.detail;

import java.util.List;

public record CanonicalPlaceDetail(
        String schemaVersion,
        String placeId,
        String name,
        String category,
        RegionProjection region,
        String address,
        CoordinatesProjection coordinates,
        List<ImageProjection> images,
        String description,
        List<String> contentTags,
        boolean savedByMe) {

    public CanonicalPlaceDetail {
        contentTags = contentTags == null ? List.of() : List.copyOf(contentTags);
    }
}

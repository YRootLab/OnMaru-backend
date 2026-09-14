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
        boolean savedByMe) {
}

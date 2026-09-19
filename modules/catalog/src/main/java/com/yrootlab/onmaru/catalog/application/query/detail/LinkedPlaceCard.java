package com.yrootlab.onmaru.catalog.application.query.detail;

public record LinkedPlaceCard(
        String placeId,
        String name,
        String category,
        String regionName,
        String thumbnailUrl,
        boolean savedByMe) {
}

package com.yrootlab.onmaru.catalog.application.query.journey;

public record ApprovedPlaceLookupItem(
        String placeId,
        String name,
        String category,
        String address,
        String regionCode,
        Double latitude,
        Double longitude) {
}

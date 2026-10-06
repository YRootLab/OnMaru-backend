package com.yrootlab.onmaru.catalog.externalplace;

public record ExternalPlaceCandidate(
        ExternalPlaceProvider provider,
        String externalId,
        String name,
        double lat,
        double lng) {
}

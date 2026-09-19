package com.yrootlab.onmaru.catalog.application.qualification;

public record CanonicalCandidate(
        String recordKey,
        String externalId,
        String name,
        CanonicalCategory category,
        double longitude,
        double latitude,
        String normalizedHash,
        String allowlistVersion
) {
}

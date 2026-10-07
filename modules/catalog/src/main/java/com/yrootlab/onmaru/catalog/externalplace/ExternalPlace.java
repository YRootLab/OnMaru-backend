package com.yrootlab.onmaru.catalog.externalplace;

import java.util.UUID;

public record ExternalPlace(
        UUID placeId,
        String publicPlaceId,
        String name,
        String regionCode,
        double lat,
        double lng,
        boolean created) {
}

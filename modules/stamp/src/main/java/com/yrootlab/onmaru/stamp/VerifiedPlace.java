package com.yrootlab.onmaru.stamp;

import java.util.UUID;

public record VerifiedPlace(UUID internalPlaceId, String publicPlaceId, String regionCode, int distanceMeters) {
}

package com.yrootlab.onmaru.journey.saved.place;

import java.time.Instant;

public record SavedPlaceState(
        String schemaVersion,
        SavedResourceType resourceType,
        String resourceId,
        String placeId,
        boolean savedByMe,
        Instant savedAt) {
}

package com.yrootlab.onmaru.stamp;

import java.time.Instant;
import java.util.UUID;

public record StampCheckIn(
        UUID id,
        String placeId,
        Instant checkedInAt,
        int distanceMeters,
        boolean alreadyCheckedIn) {
}

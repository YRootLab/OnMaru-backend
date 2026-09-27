package com.yrootlab.onmaru.insights.query;

import java.time.Instant;
import java.util.List;

public record MapHeatSpot(
        String id,
        String placeId,
        String name,
        double lat,
        double lng,
        String district,
        long visitorCount,
        double congestionScore,
        String congestionLevel,
        double surgeMultiplier,
        double intensity,
        List<Double> series,
        Instant updatedAt
) {
    public MapHeatSpot {
        series = List.copyOf(series);
    }
}

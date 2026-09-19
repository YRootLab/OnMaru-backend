package com.yrootlab.onmaru.insights.query;

import java.time.LocalDate;

public record HeatSpot(
        String id,
        String placeId,
        String name,
        RegionRef region,
        Coordinates coordinates,
        Long visitorCount,
        Double congestionScore,
        String congestionLevel,
        Double surgeMultiplier,
        String coverageStatus,
        LocalDate observedDate,
        String metric
) {
}

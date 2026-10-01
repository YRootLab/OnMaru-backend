package com.yrootlab.onmaru.insights.query;

import java.time.LocalDate;

public record HeatSpot(
        String id,
        String placeId,
        String name,
        RegionRef region,
        Coordinates coordinates,
        Long visitorCount,
        Long localCount,
        Double congestionScore,
        String congestionLevel,
        Double surgeMultiplier,
        String coverageStatus,
        LocalDate observedDate,
        String metric
) {
    public HeatSpot(
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
            String metric) {
        this(id, placeId, name, region, coordinates, visitorCount, null, congestionScore,
                congestionLevel, surgeMultiplier, coverageStatus, observedDate, metric);
    }
}

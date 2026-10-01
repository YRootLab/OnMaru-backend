package com.yrootlab.onmaru.insights.query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record MapHeatResponse(
        List<MapHeatSpot> spots,
        List<MapHeatDay> days,
        int count,
        Instant updatedAt,
        String origin,
        String coverageStatus,
        String spatialLevel,
        LocalDate observedFrom,
        LocalDate observedTo,
        String methodologyVersion
) {
    public MapHeatResponse {
        spots = List.copyOf(spots);
        days = List.copyOf(days);
    }
}

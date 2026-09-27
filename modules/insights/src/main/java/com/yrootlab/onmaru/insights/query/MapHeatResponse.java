package com.yrootlab.onmaru.insights.query;

import java.time.Instant;
import java.util.List;

public record MapHeatResponse(
        List<MapHeatSpot> spots,
        List<MapHeatDay> days,
        int count,
        Instant updatedAt
) {
    public MapHeatResponse {
        spots = List.copyOf(spots);
        days = List.copyOf(days);
    }
}

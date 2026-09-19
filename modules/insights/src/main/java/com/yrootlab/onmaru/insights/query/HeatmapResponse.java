package com.yrootlab.onmaru.insights.query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record HeatmapResponse(
        String schemaVersion,
        String coverageStatus,
        String metric,
        LocalDate observedDate,
        Instant generatedAt,
        List<HeatSpot> spots
) {

    public HeatmapResponse {
        spots = List.copyOf(spots);
    }
}

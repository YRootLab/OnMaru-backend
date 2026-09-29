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
        List<HeatSpot> spots,
        String origin,
        String spatialLevel,
        LocalDate observedFrom,
        LocalDate observedTo,
        String methodologyVersion
) {

    public HeatmapResponse(
            String schemaVersion,
            String coverageStatus,
            String metric,
            LocalDate observedDate,
            Instant generatedAt,
            List<HeatSpot> spots) {
        this(schemaVersion, coverageStatus, metric, observedDate, generatedAt, spots,
                "DERIVED_INDEX", "SIGUNGU", observedDate, observedDate, "warmth-v2");
    }

    public HeatmapResponse {
        spots = List.copyOf(spots);
    }
}

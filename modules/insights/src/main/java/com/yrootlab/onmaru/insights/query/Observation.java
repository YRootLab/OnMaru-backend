package com.yrootlab.onmaru.insights.query;

import java.time.LocalDate;

public record Observation(
        String observationId,
        RegionRef region,
        LocalDate observedDate,
        String metric,
        Number value,
        String unit,
        String spatialLevel,
        String coverageStatus
) {
}

package com.yrootlab.onmaru.insights.observation;

import java.time.Instant;
import java.time.LocalDate;

public record VisitorObservation(
        String provider,
        String regionCode,
        LocalDate basisDate,
        ObservationMetric metric,
        Long value,
        String unit,
        SpatialLevel spatialLevel,
        ObservationCoverageStatus coverageStatus,
        Instant sourceObservedAt,
        String visitorType
) {
    public VisitorObservation(
            String provider,
            String regionCode,
            LocalDate basisDate,
            ObservationMetric metric,
            Long value,
            String unit,
            SpatialLevel spatialLevel,
            ObservationCoverageStatus coverageStatus,
            Instant sourceObservedAt) {
        this(provider, regionCode, basisDate, metric, value, unit, spatialLevel,
                coverageStatus, sourceObservedAt, "TOTAL");
    }

    public VisitorObservation {
        if (visitorType == null || visitorType.isBlank()) {
            visitorType = "TOTAL";
        }
    }
}

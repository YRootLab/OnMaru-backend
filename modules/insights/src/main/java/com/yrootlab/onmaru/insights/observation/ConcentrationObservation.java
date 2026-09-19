package com.yrootlab.onmaru.insights.observation;

import java.time.LocalDate;

public record ConcentrationObservation(
        String provider,
        String targetKey,
        String regionCode,
        LocalDate basisDate,
        ObservationMetric metric,
        Double value,
        String unit,
        ObservationCoverageStatus coverageStatus
) {
}

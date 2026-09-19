package com.yrootlab.onmaru.insights.query;

import java.time.Instant;
import java.util.List;

public record ObservationPage(
        String schemaVersion,
        String coverageStatus,
        Instant generatedAt,
        List<Observation> items
) {

    public ObservationPage {
        items = List.copyOf(items);
    }
}

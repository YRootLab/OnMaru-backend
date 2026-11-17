package com.yrootlab.onmaru.journey.worker;

import java.util.List;
import java.util.Map;

public record JourneyWorkerPlan(
        List<String> orderedRefs,
        String outcome,
        Map<String, String> placeReasons,
        String summaryText) {

    public JourneyWorkerPlan {
        orderedRefs = List.copyOf(orderedRefs);
        placeReasons = placeReasons == null ? Map.of() : Map.copyOf(placeReasons);
    }

    public JourneyWorkerPlan(List<String> orderedRefs, String outcome) {
        this(orderedRefs, outcome, Map.of(), null);
    }
}

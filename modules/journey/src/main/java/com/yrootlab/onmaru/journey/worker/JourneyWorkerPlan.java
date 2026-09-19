package com.yrootlab.onmaru.journey.worker;

import java.util.List;

public record JourneyWorkerPlan(List<String> orderedRefs, String outcome) {

    public JourneyWorkerPlan {
        orderedRefs = List.copyOf(orderedRefs);
    }
}

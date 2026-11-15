package com.yrootlab.onmaru.journey.worker;

import java.util.List;

public record JourneyWorkerPlan(List<String> orderedRefs, String outcome, AiNarrative narrative) {

    public JourneyWorkerPlan {
        orderedRefs = List.copyOf(orderedRefs);
        narrative = narrative == null ? AiNarrative.empty() : narrative;
    }

    public JourneyWorkerPlan(List<String> orderedRefs, String outcome) {
        this(orderedRefs, outcome, AiNarrative.empty());
    }
}

package com.yrootlab.onmaru.journey.worker;

import java.util.List;

public record JourneyWorkerDrainResult(int processed, List<JourneyWorkerOutcome> outcomes) {

    public JourneyWorkerDrainResult {
        outcomes = List.copyOf(outcomes);
    }
}

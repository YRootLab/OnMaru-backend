package com.yrootlab.onmaru.journey.worker;

import java.util.List;
import java.util.UUID;

public record PersistJourneyResultCommand(
        UUID runId,
        UUID explorationId,
        int baseVersion,
        JourneyResultEngine engine,
        WorkerDegradedReason degradedReason,
        List<String> orderedRefs,
        String outcome) {

    public PersistJourneyResultCommand {
        orderedRefs = List.copyOf(orderedRefs);
    }
}

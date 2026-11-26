package com.yrootlab.onmaru.journey.worker;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PersistJourneyResultCommand(
        UUID runId,
        UUID explorationId,
        int baseVersion,
        JourneyResultEngine engine,
        WorkerDegradedReason degradedReason,
        List<String> orderedRefs,
        String outcome,
        Map<String, String> placeReasons,
        String summaryText,
        String resultReason) {

    public PersistJourneyResultCommand {
        orderedRefs = List.copyOf(orderedRefs);
        placeReasons = placeReasons == null ? Map.of() : Map.copyOf(placeReasons);
    }

    public PersistJourneyResultCommand(
            UUID runId,
            UUID explorationId,
            int baseVersion,
            JourneyResultEngine engine,
            WorkerDegradedReason degradedReason,
            List<String> orderedRefs,
            String outcome,
            Map<String, String> placeReasons,
            String summaryText) {
        this(runId, explorationId, baseVersion, engine, degradedReason, orderedRefs, outcome, placeReasons,
                summaryText, null);
    }

    public PersistJourneyResultCommand(
            UUID runId,
            UUID explorationId,
            int baseVersion,
            JourneyResultEngine engine,
            WorkerDegradedReason degradedReason,
            List<String> orderedRefs,
            String outcome) {
        this(runId, explorationId, baseVersion, engine, degradedReason, orderedRefs, outcome, Map.of(), null, null);
    }
}

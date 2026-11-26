package com.yrootlab.onmaru.journey.worker;

import com.yrootlab.onmaru.journey.exploration.ExplorationClarification;
import java.util.List;
import java.util.Map;

public record JourneyWorkerPlan(
        List<String> orderedRefs,
        String outcome,
        Map<String, String> placeReasons,
        String summaryText,
        ExplorationClarification clarification,
        String resultReason) {

    public JourneyWorkerPlan(
            List<String> orderedRefs,
            String outcome,
            Map<String, String> placeReasons,
            String summaryText,
            ExplorationClarification clarification) {
        this(orderedRefs, outcome, placeReasons, summaryText, clarification, null);
    }

    public JourneyWorkerPlan {
        orderedRefs = List.copyOf(orderedRefs);
        placeReasons = placeReasons == null ? Map.of() : Map.copyOf(placeReasons);
    }

    public JourneyWorkerPlan(List<String> orderedRefs, String outcome) {
        this(orderedRefs, outcome, Map.of(), null, null);
    }

    public JourneyWorkerPlan(
            List<String> orderedRefs,
            String outcome,
            Map<String, String> placeReasons,
            String summaryText) {
        this(orderedRefs, outcome, placeReasons, summaryText, null);
    }

    public static JourneyWorkerPlan clarificationRequired(ExplorationClarification clarification) {
        return new JourneyWorkerPlan(List.of(), "CLARIFICATION_REQUIRED", Map.of(), null, clarification);
    }
}

package com.yrootlab.onmaru.journey.exploration;

import java.util.List;
import java.util.Map;

public record PersistedExplorationBoard(
        String engine,
        String degradedReason,
        String outcome,
        List<String> orderedRefs,
        Map<String, String> placeReasons,
        String summaryText) {

    public PersistedExplorationBoard {
        orderedRefs = orderedRefs == null ? List.of() : List.copyOf(orderedRefs);
        placeReasons = placeReasons == null ? Map.of() : Map.copyOf(placeReasons);
    }
}

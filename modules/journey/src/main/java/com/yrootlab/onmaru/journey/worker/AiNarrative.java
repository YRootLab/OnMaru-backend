package com.yrootlab.onmaru.journey.worker;

import java.util.List;

/**
 * Explanation the AI wrote for a proposal: the course title and summary, the narration, one reason per stop,
 * and the constraints it relaxed. Carried through the worker so the reasons are not discarded.
 */
public record AiNarrative(
        String title,
        String summary,
        String narration,
        List<StopReason> stops,
        List<String> relaxedConstraints) {

    public static final int MAX_TEXT = 1000;

    public AiNarrative {
        stops = stops == null ? List.of() : List.copyOf(stops);
        relaxedConstraints = relaxedConstraints == null ? List.of() : List.copyOf(relaxedConstraints);
    }

    public static AiNarrative empty() {
        return new AiNarrative(null, null, null, List.of(), List.of());
    }

    public record StopReason(String ref, String reason) {
    }
}

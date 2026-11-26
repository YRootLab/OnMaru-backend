package com.yrootlab.onmaru.journey.exploration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * orderedRefs and placeReasons are the completed turn's public place ids and per-place reasons. The web layer
 * hydrates them into cards, so older turns keep their places after later runs overwrite the exploration board.
 */
public record ExplorationTurn(
        UUID turnId,
        String query,
        Instant createdAt,
        UUID runId,
        TurnResponse response,
        List<String> orderedRefs,
        Map<String, String> placeReasons,
        String degradedReason) {

    public ExplorationTurn {
        orderedRefs = orderedRefs == null ? List.of() : List.copyOf(orderedRefs);
        placeReasons = placeReasons == null ? Map.of() : Map.copyOf(placeReasons);
    }

    public ExplorationTurn(UUID turnId, String query, Instant createdAt, UUID runId, TurnResponse response) {
        this(turnId, query, createdAt, runId, response, List.of(), Map.of(), null);
    }

    public ExplorationTurn(
            UUID turnId, String query, Instant createdAt, UUID runId, TurnResponse response, List<String> orderedRefs) {
        this(turnId, query, createdAt, runId, response, orderedRefs, Map.of(), null);
    }
}

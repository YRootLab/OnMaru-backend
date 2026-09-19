package com.yrootlab.onmaru.journey.thread;

import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JourneyTurnMemory(
        UUID turnId,
        UUID threadId,
        UUID explorationId,
        String actorType,
        String redactedQuery,
        List<String> redactionFlags,
        UUID runId,
        ExplorationRunOutcome outcome,
        Instant createdAt) {
}

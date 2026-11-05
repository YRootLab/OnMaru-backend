package com.yrootlab.onmaru.journey.exploration;

import java.time.Instant;
import java.util.UUID;

public record ExplorationTurn(
        UUID turnId,
        String query,
        Instant createdAt,
        UUID runId,
        TurnResponse response) {
}

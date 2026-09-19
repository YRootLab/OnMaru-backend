package com.yrootlab.onmaru.journey.exploration;

import java.time.Instant;
import java.util.UUID;

public record ExplorationSnapshot(
        UUID explorationId,
        int stateVersion,
        String regionCode,
        ExplorationRun run,
        Instant updatedAt) {
}

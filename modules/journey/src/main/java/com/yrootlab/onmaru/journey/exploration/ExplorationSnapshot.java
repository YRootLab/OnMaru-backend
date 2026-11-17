package com.yrootlab.onmaru.journey.exploration;

import java.time.Instant;
import java.util.UUID;

public record ExplorationSnapshot(
        UUID explorationId,
        int stateVersion,
        String regionCode,
        ExplorationRun run,
        Instant updatedAt,
        PersistedExplorationBoard board) {

    public ExplorationSnapshot(
            UUID explorationId,
            int stateVersion,
            String regionCode,
            ExplorationRun run,
            Instant updatedAt) {
        this(explorationId, stateVersion, regionCode, run, updatedAt, null);
    }
}

package com.yrootlab.onmaru.journey.exploration;

import java.time.Instant;
import java.util.UUID;

public record ExplorationState(
        UUID id,
        ExplorationActor owner,
        int stateVersion,
        String regionCode,
        ExplorationRun latestRun,
        Instant updatedAt,
        PersistedExplorationBoard board) {

    public ExplorationState(
            UUID id,
            ExplorationActor owner,
            int stateVersion,
            String regionCode,
            ExplorationRun latestRun,
            Instant updatedAt) {
        this(id, owner, stateVersion, regionCode, latestRun, updatedAt, null);
    }

    ExplorationSnapshot snapshot() {
        return new ExplorationSnapshot(id, stateVersion, regionCode, latestRun, updatedAt, board);
    }
}

package com.yrootlab.onmaru.journey.exploration;

import com.yrootlab.onmaru.journey.actions.ResourceRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExplorationState(
        UUID id,
        ExplorationActor owner,
        int stateVersion,
        String regionCode,
        ExplorationRun latestRun,
        Instant updatedAt,
        PersistedExplorationBoard board,
        List<ResourceRef> pinnedRefs,
        List<ResourceRef> excludedRefs) {

    public ExplorationState {
        pinnedRefs = pinnedRefs == null ? List.of() : List.copyOf(pinnedRefs);
        excludedRefs = excludedRefs == null ? List.of() : List.copyOf(excludedRefs);
    }

    public ExplorationState(
            UUID id,
            ExplorationActor owner,
            int stateVersion,
            String regionCode,
            ExplorationRun latestRun,
            Instant updatedAt) {
        this(id, owner, stateVersion, regionCode, latestRun, updatedAt, null, List.of(), List.of());
    }

    public ExplorationState(
            UUID id,
            ExplorationActor owner,
            int stateVersion,
            String regionCode,
            ExplorationRun latestRun,
            Instant updatedAt,
            PersistedExplorationBoard board) {
        this(id, owner, stateVersion, regionCode, latestRun, updatedAt, board, List.of(), List.of());
    }

    ExplorationSnapshot snapshot() {
        return new ExplorationSnapshot(id, stateVersion, regionCode, latestRun, updatedAt, board);
    }
}

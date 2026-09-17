package com.yrootlab.onmaru.journey.savedjourney;

import com.yrootlab.onmaru.journey.actions.ResourceRef;

import java.util.List;
import java.util.UUID;

public record ResumeSavedJourneyResult(ResumedExploration exploration, List<ResourceRef> unavailableRefs) {
    public ResumeSavedJourneyResult {
        unavailableRefs = List.copyOf(unavailableRefs);
    }

    public record ResumedExploration(
            UUID explorationId,
            int stateVersion,
            String regionCode,
            List<ResourceRef> orderedRefs,
            List<ResourceRef> pinnedRefs,
            List<ResourceRef> excludedRefs) {
        public ResumedExploration {
            orderedRefs = List.copyOf(orderedRefs);
            pinnedRefs = List.copyOf(pinnedRefs);
            excludedRefs = List.copyOf(excludedRefs);
        }
    }
}

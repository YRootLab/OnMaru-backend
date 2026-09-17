package com.yrootlab.onmaru.journey.savedjourney;

import com.yrootlab.onmaru.journey.actions.ResourceRef;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SavedJourneySnapshot(
        UUID sourceExplorationId,
        int sourceVersion,
        String title,
        String regionCode,
        List<ResourceRef> orderedRefs,
        List<ResourceRef> pinnedRefs,
        List<ResourceRef> excludedRefs,
        Instant updatedAt) {

    public SavedJourneySnapshot {
        orderedRefs = List.copyOf(orderedRefs);
        pinnedRefs = List.copyOf(pinnedRefs);
        excludedRefs = List.copyOf(excludedRefs);
    }

    public static SavedJourneySnapshot seed(
            UUID sourceExplorationId,
            int sourceVersion,
            String title,
            String regionCode,
            List<ResourceRef> orderedRefs,
            List<ResourceRef> pinnedRefs,
            List<ResourceRef> excludedRefs,
            Instant updatedAt) {
        return new SavedJourneySnapshot(
                sourceExplorationId,
                sourceVersion,
                title,
                regionCode,
                orderedRefs,
                pinnedRefs,
                excludedRefs,
                updatedAt);
    }
}

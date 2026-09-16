package com.yrootlab.onmaru.journey.actions;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

public record JourneyProposal(UUID id, int baseVersion, List<ResourceRef> orderedRefs, Instant expiresAt) {
    public JourneyProposal {
        if (id == null || baseVersion < 0 || expiresAt == null || orderedRefs == null || orderedRefs.isEmpty()
                || orderedRefs.size() > 3 || new LinkedHashSet<>(orderedRefs).size() != orderedRefs.size()) {
            throw new IllegalArgumentException("proposal is invalid");
        }
        orderedRefs = List.copyOf(orderedRefs);
    }
}

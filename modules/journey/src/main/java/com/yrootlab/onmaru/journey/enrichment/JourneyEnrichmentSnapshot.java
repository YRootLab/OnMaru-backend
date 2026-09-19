package com.yrootlab.onmaru.journey.enrichment;

import com.yrootlab.onmaru.journey.actions.ResourceRef;

import java.time.Instant;
import java.util.UUID;

public record JourneyEnrichmentSnapshot(
        UUID snapshotId,
        UUID explorationId,
        int stateVersion,
        ResourceRef resourceRef,
        String sourceManifestHash,
        JourneyEnrichment enrichment,
        Instant createdAt) {
}

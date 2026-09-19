package com.yrootlab.onmaru.journey.enrichment;

import com.yrootlab.onmaru.journey.actions.ResourceRef;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JourneyEnrichmentStore {

    JourneyEnrichmentSnapshot save(JourneyEnrichmentSnapshot snapshot);

    Optional<JourneyEnrichmentSnapshot> findLatest(UUID explorationId, ResourceRef resourceRef);

    List<JourneyEnrichmentSnapshot> listByExploration(UUID explorationId);
}

package com.yrootlab.onmaru.journey.enrichment;

import com.yrootlab.onmaru.journey.actions.ResourceRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryJourneyEnrichmentStore implements JourneyEnrichmentStore {

    private final Map<UUID, List<JourneyEnrichmentSnapshot>> snapshotsByExploration = new ConcurrentHashMap<>();

    @Override
    public JourneyEnrichmentSnapshot save(JourneyEnrichmentSnapshot snapshot) {
        if (snapshot == null || snapshot.explorationId() == null) {
            throw new IllegalArgumentException("snapshot or explorationId cannot be null");
        }
        snapshotsByExploration.computeIfAbsent(snapshot.explorationId(), id -> Collections.synchronizedList(new ArrayList<>()))
                .add(snapshot);
        return snapshot;
    }

    @Override
    public Optional<JourneyEnrichmentSnapshot> findLatest(UUID explorationId, ResourceRef resourceRef) {
        if (explorationId == null || resourceRef == null) {
            return Optional.empty();
        }
        var list = snapshotsByExploration.get(explorationId);
        if (list == null) {
            return Optional.empty();
        }
        synchronized (list) {
            return list.stream()
                    .filter(s -> resourceRef.equals(s.resourceRef()))
                    .max(Comparator.comparingInt(JourneyEnrichmentSnapshot::stateVersion)
                            .thenComparing(JourneyEnrichmentSnapshot::createdAt));
        }
    }

    @Override
    public List<JourneyEnrichmentSnapshot> listByExploration(UUID explorationId) {
        if (explorationId == null) {
            return List.of();
        }
        var list = snapshotsByExploration.get(explorationId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }
}

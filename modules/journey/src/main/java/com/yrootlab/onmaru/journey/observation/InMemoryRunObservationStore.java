package com.yrootlab.onmaru.journey.observation;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRunObservationStore implements RunObservationStore {

    private final Map<UUID, RunObservation> store = new ConcurrentHashMap<>();

    @Override
    public void save(RunObservation observation) {
        if (observation == null) {
            return;
        }
        store.put(observation.runId(), observation);
    }

    @Override
    public Optional<RunObservation> findByRunId(UUID runId) {
        if (runId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(runId));
    }

    public void clear() {
        store.clear();
    }

    public int size() {
        return store.size();
    }
}

package com.yrootlab.onmaru.journey.exploration;

import java.util.ArrayList;
import java.util.List;

public final class InMemoryExplorationRunDispatcher implements ExplorationRunDispatcher {

    private final List<ExplorationRunRequest> requests = new ArrayList<>();

    @Override
    public synchronized void dispatch(ExplorationRunRequest request) {
        requests.add(request);
    }

    public synchronized int dispatchCount() {
        return requests.size();
    }

    public synchronized List<ExplorationRunRequest> requests() {
        return List.copyOf(requests);
    }

    public synchronized void clear() {
        requests.clear();
    }
}

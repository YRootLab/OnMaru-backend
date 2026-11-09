package com.yrootlab.onmaru.journey.exploration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Records dispatches for tests and diagnostics only. Production work is queued by the exploration store
 * (QUEUED run rows), so this keeps just a count and a bounded window of recent requests.
 */
public final class InMemoryExplorationRunDispatcher implements ExplorationRunDispatcher {

    static final int RECENT_LIMIT = 100;

    private final Deque<ExplorationRunRequest> recent = new ArrayDeque<>();
    private int dispatched;

    @Override
    public synchronized void dispatch(ExplorationRunRequest request) {
        dispatched++;
        recent.addLast(request);
        if (recent.size() > RECENT_LIMIT) {
            recent.removeFirst();
        }
    }

    public synchronized int dispatchCount() {
        return dispatched;
    }

    public synchronized List<ExplorationRunRequest> requests() {
        return List.copyOf(recent);
    }

    public synchronized void clear() {
        recent.clear();
        dispatched = 0;
    }
}

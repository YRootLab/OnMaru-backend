package com.yrootlab.onmaru.journey.worker;

import java.util.ArrayDeque;
import java.util.Optional;

public final class InMemoryJourneyWorkerQueue implements JourneyWorkerQueue {

    private final ArrayDeque<JourneyWorkerRequest> requests = new ArrayDeque<>();

    @Override
    public synchronized void enqueue(JourneyWorkerRequest request) {
        requests.addLast(request);
    }

    @Override
    public synchronized Optional<JourneyWorkerRequest> poll() {
        return Optional.ofNullable(requests.pollFirst());
    }
}

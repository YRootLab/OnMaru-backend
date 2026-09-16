package com.yrootlab.onmaru.journey.worker;

import java.util.Optional;

public interface JourneyWorkerQueue {

    void enqueue(JourneyWorkerRequest request);

    Optional<JourneyWorkerRequest> poll();
}

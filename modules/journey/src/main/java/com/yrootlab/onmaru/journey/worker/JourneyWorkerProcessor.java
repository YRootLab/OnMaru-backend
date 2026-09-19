package com.yrootlab.onmaru.journey.worker;

@FunctionalInterface
public interface JourneyWorkerProcessor {

    JourneyWorkerOutcome process(JourneyWorkerRequest request);
}

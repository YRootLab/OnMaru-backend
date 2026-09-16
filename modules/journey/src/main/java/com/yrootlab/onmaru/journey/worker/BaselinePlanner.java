package com.yrootlab.onmaru.journey.worker;

@FunctionalInterface
public interface BaselinePlanner {

    JourneyWorkerPlan plan(CandidatePayload payload);
}

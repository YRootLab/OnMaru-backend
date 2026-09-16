package com.yrootlab.onmaru.journey.worker;

@FunctionalInterface
public interface JourneyCandidateProvider {

    CandidatePayload retrieve(JourneyWorkerRequest request);
}

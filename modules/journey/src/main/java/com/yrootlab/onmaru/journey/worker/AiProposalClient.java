package com.yrootlab.onmaru.journey.worker;

@FunctionalInterface
public interface AiProposalClient {

    JourneyWorkerPlan propose(JourneyWorkerRequest request, CandidatePayload payload);
}

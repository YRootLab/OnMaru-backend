package com.yrootlab.onmaru.journey.worker;

import java.util.function.Consumer;

@FunctionalInterface
public interface AiProposalClient {

    JourneyWorkerPlan propose(JourneyWorkerRequest request, CandidatePayload payload);

    default JourneyWorkerPlan propose(JourneyWorkerRequest request, CandidatePayload payload, Consumer<String> textDelta) {
        return propose(request, payload);
    }
}

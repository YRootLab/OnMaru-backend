package com.yrootlab.onmaru.journey.worker;

/** Uses the primary provider when it has candidates for the request, otherwise the fallback (e.g., catalog). */
public final class FallbackJourneyCandidateProvider implements JourneyCandidateProvider {

    private final JourneyCandidateProvider primary;
    private final JourneyCandidateProvider fallback;

    public FallbackJourneyCandidateProvider(JourneyCandidateProvider primary, JourneyCandidateProvider fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public CandidatePayload retrieve(JourneyWorkerRequest request) {
        var fromPrimary = primary.retrieve(request);
        if (fromPrimary != null && !fromPrimary.candidates().isEmpty()) {
            return fromPrimary;
        }
        return fallback.retrieve(request);
    }
}

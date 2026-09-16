package com.yrootlab.onmaru.journey.worker;

public final class DefaultBaselinePlanner implements BaselinePlanner {

    private static final int BOARD_LIMIT = 3;

    @Override
    public JourneyWorkerPlan plan(CandidatePayload payload) {
        var refs = payload.candidates().stream()
                .map(JourneyCandidate::ref)
                .limit(BOARD_LIMIT)
                .toList();
        if (refs.isEmpty()) {
            return new JourneyWorkerPlan(refs, "NO_RESULTS");
        }
        return new JourneyWorkerPlan(refs, "INITIAL_BOARD");
    }
}

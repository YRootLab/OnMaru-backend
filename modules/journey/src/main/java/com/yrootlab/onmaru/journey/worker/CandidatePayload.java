package com.yrootlab.onmaru.journey.worker;

import java.util.List;

public record CandidatePayload(String datasetRevision, List<JourneyCandidate> candidates) {

    public CandidatePayload {
        candidates = List.copyOf(candidates);
    }
}

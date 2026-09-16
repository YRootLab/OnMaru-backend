package com.yrootlab.onmaru.journey.worker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryJourneyCandidateProvider implements JourneyCandidateProvider {

    private final Map<Key, List<JourneyCandidate>> candidatesByDatasetAndRegion = new LinkedHashMap<>();

    public synchronized void put(String datasetRevision, String regionCode, List<JourneyCandidate> candidates) {
        candidatesByDatasetAndRegion.put(new Key(datasetRevision, regionCode), List.copyOf(candidates));
    }

    @Override
    public synchronized CandidatePayload retrieve(JourneyWorkerRequest request) {
        return new CandidatePayload(
                request.datasetRevision(),
                candidatesByDatasetAndRegion.getOrDefault(
                        new Key(request.datasetRevision(), request.regionCode()),
                        List.of()));
    }

    private record Key(String datasetRevision, String regionCode) {
    }
}

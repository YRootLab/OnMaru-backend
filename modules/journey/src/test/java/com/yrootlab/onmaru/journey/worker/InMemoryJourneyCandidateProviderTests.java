package com.yrootlab.onmaru.journey.worker;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryJourneyCandidateProviderTests {

    @Test
    void returnsCandidatesForMatchingDatasetAndRegionOnly() {
        var provider = new InMemoryJourneyCandidateProvider();
        provider.put(
                "dataset-2026-09-16",
                "seoul-jongno",
                List.of(new JourneyCandidate("place:001"), new JourneyCandidate("place:002")));
        provider.put(
                "dataset-2026-09-16",
                "busan-junggu",
                List.of(new JourneyCandidate("place:999")));

        var payload = provider.retrieve(request("dataset-2026-09-16", "seoul-jongno"));

        assertThat(payload.datasetRevision()).isEqualTo("dataset-2026-09-16");
        assertThat(payload.candidates()).containsExactly(
                new JourneyCandidate("place:001"),
                new JourneyCandidate("place:002"));
    }

    @Test
    void returnsEmptyPayloadWhenRegionHasNoCandidates() {
        var provider = new InMemoryJourneyCandidateProvider();

        var payload = provider.retrieve(request("dataset-2026-09-16", "seoul-jongno"));

        assertThat(payload.datasetRevision()).isEqualTo("dataset-2026-09-16");
        assertThat(payload.candidates()).isEmpty();
    }

    private static JourneyWorkerRequest request(String datasetRevision, String regionCode) {
        return new JourneyWorkerRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "MEMBER:123",
                1,
                datasetRevision,
                regionCode,
                "조용한 한옥 코스",
                0,
                "req-001",
                "4bf92f3577b34da6a3ce929d0e0e4736",
                Instant.parse("2026-09-16T00:00:20Z"));
    }
}

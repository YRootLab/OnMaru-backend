package com.yrootlab.onmaru.journey.worker;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackJourneyCandidateProviderTests {

    @Test
    void usesPrimaryWhenItHasCandidatesAndFallbackOtherwise() {
        var primary = new InMemoryJourneyCandidateProvider();
        primary.put("rev-1", "seoul", List.of(new JourneyCandidate("place-a")));
        var fallbackCalls = new int[] {0};
        JourneyCandidateProvider fallback = request -> {
            fallbackCalls[0]++;
            return new CandidatePayload(request.datasetRevision(), List.of(new JourneyCandidate("catalog-1")));
        };
        var provider = new FallbackJourneyCandidateProvider(primary, fallback);

        assertThat(provider.retrieve(request("rev-1", "seoul")).candidates()).extracting(JourneyCandidate::ref)
                .containsExactly("place-a");
        assertThat(fallbackCalls[0]).isZero();

        assertThat(provider.retrieve(request("rev-1", "busan")).candidates()).extracting(JourneyCandidate::ref)
                .containsExactly("catalog-1");
        assertThat(fallbackCalls[0]).isEqualTo(1);
    }

    private static JourneyWorkerRequest request(String dataset, String region) {
        return new JourneyWorkerRequest(UUID.randomUUID(), UUID.randomUUID(), "actor", 1, dataset, region,
                "query", 0, "req-1", "0af7651916cd43dd8448eb211c80319c", Instant.parse("2026-11-09T00:00:20Z"));
    }
}

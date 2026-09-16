package com.yrootlab.onmaru.journey.worker;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyWorkerRunnerTests {

    @Test
    void drainsQueuedRequestsInFifoOrderAndReturnsOutcomes() {
        var queue = new InMemoryJourneyWorkerQueue();
        var processed = new ArrayList<UUID>();
        var runner = new JourneyWorkerRunner(queue, request -> {
            processed.add(request.runId());
            return request.runId().toString().endsWith("1")
                    ? JourneyWorkerOutcome.COMPLETED_LLM
                    : JourneyWorkerOutcome.COMPLETED_BASELINE;
        });
        var first = request("11111111-1111-1111-1111-111111111111");
        var second = request("22222222-2222-2222-2222-222222222222");
        queue.enqueue(second);
        queue.enqueue(first);

        var result = runner.drain();

        assertThat(processed).containsExactly(second.runId(), first.runId());
        assertThat(result.processed()).isEqualTo(2);
        assertThat(result.outcomes()).containsExactly(
                JourneyWorkerOutcome.COMPLETED_BASELINE,
                JourneyWorkerOutcome.COMPLETED_LLM);
        assertThat(queue.poll()).isEmpty();
    }

    private static JourneyWorkerRequest request(String runId) {
        return new JourneyWorkerRequest(
                UUID.fromString(runId),
                UUID.randomUUID(),
                "MEMBER:123",
                1,
                "dataset-2026-09-16",
                "seoul-jongno",
                "조용한 한옥 코스",
                0,
                "req-001",
                "4bf92f3577b34da6a3ce929d0e0e4736",
                Instant.parse("2026-09-16T00:00:20Z"));
    }
}

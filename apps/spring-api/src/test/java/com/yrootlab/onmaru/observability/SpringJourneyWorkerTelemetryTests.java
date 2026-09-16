package com.yrootlab.onmaru.observability;

import com.yrootlab.onmaru.journey.worker.WorkerTelemetryEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringJourneyWorkerTelemetryTests {

    @Test
    void forwardsWorkerEventsToTelemetrySinkWithoutSensitiveFields() {
        var sink = new InMemoryTelemetrySink();
        var telemetry = new SpringJourneyWorkerTelemetry(sink);

        telemetry.record(new WorkerTelemetryEvent(
                "journey.worker.completed",
                Map.of(
                        "run.id", "run-123",
                        "engine", "BASELINE",
                        "degraded.reason", "AI_TIMEOUT")));

        assertThat(sink.events()).containsExactly(new TelemetryEvent(
                "journey.worker.completed",
                Map.of(
                        "run.id", "run-123",
                        "engine", "BASELINE",
                        "degraded.reason", "AI_TIMEOUT")));
        assertThat(sink.events().getFirst().attributes().keySet())
                .doesNotContain("query", "token", "cookie", "evidence.body");
    }
}

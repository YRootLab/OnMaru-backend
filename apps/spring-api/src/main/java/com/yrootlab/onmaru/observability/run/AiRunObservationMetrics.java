package com.yrootlab.onmaru.observability.run;

import com.yrootlab.onmaru.journey.observation.ObservationOutcome;
import com.yrootlab.onmaru.journey.observation.ObservationRoute;
import com.yrootlab.onmaru.journey.observation.StageObservation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;

/**
 * AI Run Stage-level Low-Cardinality Metrics Recorder.
 * Ensures stage latency, outcome, and error counts are recorded with strict low-cardinality tags.
 * Raw queries, prompts, tokens, member IDs, run IDs, and trace IDs MUST NEVER be used as tags.
 */
public final class AiRunObservationMetrics {

    private static final String METRIC_STAGE_DURATION = "onmaru.ai.run.stage.duration";
    private static final String METRIC_STAGE_TOTAL = "onmaru.ai.run.stage.total";
    private static final String METRIC_RUN_TOTAL = "onmaru.ai.run.total";

    private final MeterRegistry meterRegistry;

    public AiRunObservationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    public void recordStageObservation(String route, StageObservation stageObservation) {
        if (stageObservation == null) {
            return;
        }

        String stage = normalizeTag(stageObservation.stage());
        String routeTag = normalizeTag(route);
        String status = stageObservation.status() != null ? stageObservation.status().name().toLowerCase() : "unknown";
        String errorCode = stageObservation.errorCode() != null && !stageObservation.errorCode().isBlank()
                ? stageObservation.errorCode().toLowerCase()
                : "none";

        if (stageObservation.durationMs() != null) {
            Timer.builder(METRIC_STAGE_DURATION)
                    .description("AI run stage execution duration")
                    .tag("stage", stage)
                    .tag("route", routeTag)
                    .tag("status", status)
                    .register(meterRegistry)
                    .record(Duration.ofMillis(Math.max(0, stageObservation.durationMs())));
        }

        // 2. Stage outcome / error counter
        Counter.builder(METRIC_STAGE_TOTAL)
                .description("AI run stage executions total")
                .tag("stage", stage)
                .tag("route", routeTag)
                .tag("status", status)
                .tag("error_code", errorCode)
                .register(meterRegistry)
                .increment();
    }

    public void recordRunOutcome(ObservationRoute route, ObservationOutcome outcome) {
        String routeTag = route != null ? route.name().toLowerCase() : "unknown";
        String outcomeTag = outcome != null ? outcome.name().toLowerCase() : "unknown";

        Counter.builder(METRIC_RUN_TOTAL)
                .description("AI runs total by route and outcome")
                .tag("route", routeTag)
                .tag("outcome", outcomeTag)
                .register(meterRegistry)
                .increment();
    }

    private static String normalizeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.toLowerCase().replace(' ', '_').replace('-', '_');
    }
}

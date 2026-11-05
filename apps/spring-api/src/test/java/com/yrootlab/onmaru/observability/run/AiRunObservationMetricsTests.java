package com.yrootlab.onmaru.observability.run;

import com.yrootlab.onmaru.journey.observation.ObservationOutcome;
import com.yrootlab.onmaru.journey.observation.ObservationRoute;
import com.yrootlab.onmaru.journey.observation.StageExecutionStatus;
import com.yrootlab.onmaru.journey.observation.StageObservation;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AiRunObservationMetricsTests {

    private SimpleMeterRegistry meterRegistry;
    private AiRunObservationMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new AiRunObservationMetrics(meterRegistry);
    }

    @Test
    @DisplayName("AI 실행 단계 메트릭 기록 시 저카디널리티 태그만 포함되고 민감 정보 및 고카디널리티 태그가 없어야 한다")
    void recordsStageMetricsWithLowCardinalityTagsOnly() {
        var stageObservation = StageObservation.of(
                "RETRIEVAL",
                StageExecutionStatus.SUCCESS,
                null,
                null,
                142,
                10,
                5
        );

        metrics.recordStageObservation("HYBRID", stageObservation);

        // Verify timer recorded
        var timer = meterRegistry.find("onmaru.ai.run.stage.duration")
                .tag("stage", "retrieval")
                .tag("route", "hybrid")
                .tag("status", "success")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(142.0);

        // Verify counter recorded
        var counter = meterRegistry.find("onmaru.ai.run.stage.total")
                .tag("stage", "retrieval")
                .tag("route", "hybrid")
                .tag("status", "success")
                .tag("error_code", "none")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        // Ensure NO forbidden high-cardinality or PII tags exist across any registered meters
        Set<String> forbiddenTagKeys = Set.of(
                "runid", "run_id", "run.id",
                "memberid", "member_id", "member.id",
                "traceid", "trace_id", "trace.id",
                "query", "prompt", "token", "location", "credential", "auth"
        );

        for (Meter meter : meterRegistry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                String keyLower = tag.getKey().toLowerCase();
                assertThat(forbiddenTagKeys).doesNotContain(keyLower);
                // Tag values must also not contain raw query or credentials
                assertThat(tag.getValue()).doesNotContain("SELECT", "Bearer", "jwt", "prompt");
            }
        }
    }

    @Test
    @DisplayName("실행 결과 집계 메트릭이 route와 outcome 저카디널리티 태그로 등록된다")
    void recordsRunOutcomeMetrics() {
        metrics.recordRunOutcome(ObservationRoute.HYBRID, ObservationOutcome.SUCCESS);
        metrics.recordRunOutcome(ObservationRoute.SQL_ONLY, ObservationOutcome.FAILED);

        var successCounter = meterRegistry.find("onmaru.ai.run.total")
                .tag("route", "hybrid")
                .tag("outcome", "success")
                .counter();
        assertThat(successCounter).isNotNull();
        assertThat(successCounter.count()).isEqualTo(1.0);

        var failCounter = meterRegistry.find("onmaru.ai.run.total")
                .tag("route", "sql_only")
                .tag("outcome", "failed")
                .counter();
        assertThat(failCounter).isNotNull();
        assertThat(failCounter.count()).isEqualTo(1.0);
    }
}

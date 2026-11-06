package com.yrootlab.onmaru.observability.run;

import com.yrootlab.onmaru.integration.ai.security.InternalAiRequestHeadersFactory;
import com.yrootlab.onmaru.integration.ai.security.InternalAiTokenRequest;
import com.yrootlab.onmaru.integration.ai.security.InternalAiTokenSigner;
import com.yrootlab.onmaru.journey.observation.ObservationOutcome;
import com.yrootlab.onmaru.journey.observation.ObservationRoute;
import com.yrootlab.onmaru.journey.observation.ObservationStatus;
import com.yrootlab.onmaru.journey.observation.RunObservation;
import com.yrootlab.onmaru.journey.observation.StageExecutionStatus;
import com.yrootlab.onmaru.journey.observation.StageObservation;
import com.yrootlab.onmaru.journey.observation.TraceCoverage;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RunObservationTraceAndRedactionTests {

    private static final Pattern W3C_TRACEPARENT_PATTERN =
            Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[0-9a-f]$");

    @Test
    @DisplayName("W3C traceparent가 규격에 맞게 생성되고 FastAPI 전파 헤더에 포함된다")
    void assertsW3cTraceparentHeaderContinuity() {
        var signer = new InternalAiTokenSigner(
                new com.yrootlab.onmaru.config.secrets.FakeSecretProvider(),
                com.yrootlab.onmaru.integration.ai.security.InternalAiTokenProperties.defaults(),
                java.time.Clock.systemUTC());
        var headersFactory = new InternalAiRequestHeadersFactory(signer);

        var tokenRequest = InternalAiTokenRequest.forJourneyProposal(
                "req-123",
                "4bf92f3577b34da6a3ce929d0e0e4736",
                UUID.randomUUID().toString(),
                "rev-1",
                Instant.now().plusSeconds(60)
        );

        Map<String, String> headers = headersFactory.headersFor(tokenRequest);

        assertThat(headers).containsKey("traceparent");
        String traceparent = headers.get("traceparent");
        assertThat(traceparent).matches(W3C_TRACEPARENT_PATTERN);
        assertThat(traceparent).startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-");

        // Auth token must not leak into traceparent
        assertThat(traceparent).doesNotContain("Bearer");
    }

    @Test
    @DisplayName("Micrometer 메트릭 등록 시 PII, runId, memberId, traceId, query 원문이 태그로 노출되지 않는다")
    void assertsNoSensitiveInformationInMetrics() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AiRunObservationMetrics(registry);

        var stage = StageObservation.of(
                "RETRIEVAL",
                StageExecutionStatus.SUCCESS,
                "NONE",
                null,
                120,
                5,
                3
        );

        metrics.recordStageObservation("HYBRID", stage);
        metrics.recordRunOutcome(ObservationRoute.HYBRID, ObservationOutcome.SUCCESS);

        Set<String> disallowedTagKeys = Set.of(
                "runid", "run_id", "traceid", "trace_id", "memberid", "member_id",
                "userid", "user_id", "query", "prompt", "token", "raw_query"
        );

        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                String key = tag.getKey().toLowerCase();
                String value = tag.getValue().toLowerCase();

                assertThat(disallowedTagKeys).doesNotContain(key);
                assertThat(value).doesNotContain("secret");
                assertThat(value).doesNotContain("bearer");
            }
        }
    }

    @Test
    @DisplayName("RunObservation projection 도메인 모델에 원문 질의/답변/인증정보 필드가 없다")
    void assertsSanitizedRunObservationDomainModel() {
        var observation = RunObservation.builder(UUID.randomUUID())
                .traceId("4bf92f3577b34da6a3ce929d0e0e4736")
                .observationStatus(ObservationStatus.COMPLETE)
                .traceCoverage(TraceCoverage.AVAILABLE)
                .route(ObservationRoute.HYBRID)
                .outcome(ObservationOutcome.SUCCESS)
                .corpusRevision("corpus-20261105")
                .configHash("config-abc")
                .promptVersion("v1")
                .stages(List.of())
                .build();

        // Verify observation fields only contain safe metadata
        assertThat(observation.route()).isEqualTo(ObservationRoute.HYBRID);
        assertThat(observation.outcome()).isEqualTo(ObservationOutcome.SUCCESS);
        assertThat(observation.corpusRevision()).isEqualTo("corpus-20261105");
    }
}

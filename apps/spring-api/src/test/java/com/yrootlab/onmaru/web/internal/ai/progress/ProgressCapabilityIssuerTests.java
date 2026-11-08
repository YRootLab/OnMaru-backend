package com.yrootlab.onmaru.web.internal.ai.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.SecretBundle;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.ApprovedPlanBinding;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.PlanProposal;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressCapabilityIssuerTests {
    private static final String SECRET = "progress-capability-secret-with-enough-entropy";
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneOffset.UTC);
    private final SecretProvider secrets = name -> new SecretBundle(name, SECRET, Optional.empty());

    @Test
    void issuesRunPlanScopeAudienceAndDeadlineBoundCapability() throws Exception {
        var binding = new ApprovedPlanBinding(ProgressTestFixtures.plan());
        var issuer = new HmacProgressCapabilityIssuer(secrets, clock);

        String token = issuer.issue(binding, clock.instant().plusSeconds(30));
        var payload = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));

        assertThat(payload.path("runId").asText()).isEqualTo(binding.plan().runId().toString());
        assertThat(payload.path("planHash").asText()).isEqualTo(binding.plan().planHash());
        assertThat(payload.path("scope").asText()).isEqualTo("journey.run.progress.write");
        assertThat(payload.path("aud").asText()).isEqualTo("spring-ai-progress");
        assertThat(payload.path("exp").asLong()).isEqualTo(clock.instant().plusSeconds(30).getEpochSecond());
        assertThat(new InternalAiProgressVerifier(secrets, "internal-ai-service-token", clock)
                .verifyToken(token, binding.plan().runId(), binding.plan().planHash())).isTrue();
    }
}

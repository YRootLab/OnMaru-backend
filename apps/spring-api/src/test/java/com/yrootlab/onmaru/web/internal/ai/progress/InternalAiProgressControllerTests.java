package com.yrootlab.onmaru.web.internal.ai.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.config.secrets.SecretBundle;
import com.yrootlab.onmaru.journey.events.JourneyRunEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalAiProgressControllerTests {

    private static final String SECRET_KEY = "test-internal-ai-secret-key-current";
    private static final String SECRET_NAME = "internal-ai-service-token";

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private TestEventSink eventSink;
    private InternalAiProgressVerifier verifier;
    private Clock clock;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        eventSink = new TestEventSink();
        clock = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC);

        SecretProvider secretProvider = name -> new SecretBundle(name, SECRET_KEY, java.util.Optional.empty());
        verifier = new InternalAiProgressVerifier(secretProvider, SECRET_NAME, clock);

        var controller = new InternalAiProgressController(verifier, eventSink);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void acceptsValidProgressCallbackAndPublishesMappedStage() throws Exception {
        var runId = UUID.randomUUID();
        var planHash = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        var token = generateToken(runId, planHash, clock.instant().plusSeconds(60));

        var payload = new InternalAiProgressPayload(
                "internal.ai.progress.v1",
                runId,
                planHash,
                1,
                "PREPARING_INPUT"
        );

        mockMvc.perform(post("/internal/v1/ai/runs/" + runId + "/progress")
                        .header("X-Run-Progress-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        assertThat(eventSink.stages).hasSize(1);
        assertThat(eventSink.stages.get(0).stage()).isEqualTo("INTERPRETING");
    }

    @Test
    void rejectsInvalidOrExpiredToken() throws Exception {
        var runId = UUID.randomUUID();
        var planHash = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        var expiredToken = generateToken(runId, planHash, clock.instant().minusSeconds(10));

        var payload = new InternalAiProgressPayload(
                "internal.ai.progress.v1",
                runId,
                planHash,
                1,
                "PREPARING_INPUT"
        );

        mockMvc.perform(post("/internal/v1/ai/runs/" + runId + "/progress")
                        .header("X-Run-Progress-Token", expiredToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidSchemaVersionOrMismatchedRunId() throws Exception {
        var runId = UUID.randomUUID();
        var planHash = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        var token = generateToken(runId, planHash, clock.instant().plusSeconds(60));

        var wrongSchemaPayload = new InternalAiProgressPayload(
                "internal.ai.progress.v2",
                runId,
                planHash,
                1,
                "PREPARING_INPUT"
        );

        mockMvc.perform(post("/internal/v1/ai/runs/" + runId + "/progress")
                        .header("X-Run-Progress-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongSchemaPayload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ignoresDuplicateOrStaleOrdinalIdempotently() throws Exception {
        var runId = UUID.randomUUID();
        var planHash = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        var token = generateToken(runId, planHash, clock.instant().plusSeconds(60));

        var payload1 = new InternalAiProgressPayload("internal.ai.progress.v1", runId, planHash, 2, "RETRIEVING");
        var payload2 = new InternalAiProgressPayload("internal.ai.progress.v1", runId, planHash, 1, "PREPARING_INPUT");

        mockMvc.perform(post("/internal/v1/ai/runs/" + runId + "/progress")
                        .header("X-Run-Progress-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload1)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/internal/v1/ai/runs/" + runId + "/progress")
                        .header("X-Run-Progress-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload2)))
                .andExpect(status().isOk());

        assertThat(eventSink.stages).hasSize(1);
        assertThat(eventSink.stages.get(0).stage()).isEqualTo("RETRIEVING");
    }

    private String generateToken(UUID runId, String planHash, Instant expiry) throws Exception {
        var b64 = Base64.getUrlEncoder().withoutPadding();
        var header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        var claimsJson = objectMapper.writeValueAsString(Map.of(
                "runId", runId.toString(),
                "planHash", planHash,
                "exp", expiry.getEpochSecond()
        ));
        var payload = b64.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
        var signingInput = header + "." + payload;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var sig = b64.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        return signingInput + "." + sig;
    }

    private static class TestEventSink implements JourneyRunEventSink {
        record StageEvent(UUID runId, String status, String stage) {}
        final List<StageEvent> stages = new ArrayList<>();

        @Override
        public com.yrootlab.onmaru.journey.events.JourneyRunEvent stage(UUID runId, String status, String stage) {
            stages.add(new StageEvent(runId, status, stage));
            return null;
        }

        @Override
        public com.yrootlab.onmaru.journey.events.JourneyRunEvent terminal(UUID runId, String status, String outcome) { return null; }
    }
}

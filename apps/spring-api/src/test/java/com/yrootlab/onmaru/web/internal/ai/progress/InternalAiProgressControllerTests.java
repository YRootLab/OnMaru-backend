package com.yrootlab.onmaru.web.internal.ai.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.SecretBundle;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.integration.ai.progress.AiRunProgressStore;
import com.yrootlab.onmaru.journey.events.JourneyRunEvent;
import com.yrootlab.onmaru.journey.events.JourneyRunEventSink;
import com.yrootlab.onmaru.journey.run.JourneyRunStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalAiProgressControllerTests {

    private static final String SECRET_KEY = "test-internal-ai-secret-key-current";
    private static final String PLAN_HASH = "sha256:" + "a".repeat(64);
    private static final String OTHER_PLAN_HASH = "sha256:" + "b".repeat(64);

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC);
    private final FakeProgressStore store = new FakeProgressStore();
    private final TestEventSink eventSink = new TestEventSink();
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SecretProvider secretProvider = name -> new SecretBundle(name, SECRET_KEY, Optional.empty());
        var verifier = new InternalAiProgressVerifier(secretProvider, HmacProgressCapabilityIssuer.SECRET_NAME, clock);
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalAiProgressController(verifier, store, eventSink)).build();
        store.bound = Optional.of(PLAN_HASH);
    }

    @Test
    void appliedFirstOrdinalPublishesMappedStage() throws Exception {
        store.next = new AiRunProgressStore.Result(AiRunProgressStore.Decision.APPLIED, JourneyRunStage.INTERPRETING);

        callProgress(PLAN_HASH, 1, "PREPARING_INPUT", token(PLAN_HASH, runId, clock.instant().plusSeconds(60)))
                .andExpect(status().isOk());

        assertThat(store.recorded).containsExactly(new Recorded(runId, PLAN_HASH, 1, JourneyRunStage.INTERPRETING));
        assertThat(eventSink.stages).containsExactly(JourneyRunStage.INTERPRETING.name());
    }

    @Test
    void appliedWithoutAdvancingEffectiveStageDoesNotPublish() throws Exception {
        store.next = new AiRunProgressStore.Result(AiRunProgressStore.Decision.APPLIED, null);

        callProgress(PLAN_HASH, 2, "RETRIEVING", token(PLAN_HASH, runId, clock.instant().plusSeconds(60)))
                .andExpect(status().isOk());

        assertThat(eventSink.stages).isEmpty();
    }

    @Test
    void duplicateStaleAndInactiveAreAcknowledgedWithoutPublishing() throws Exception {
        for (var decision : List.of(AiRunProgressStore.Decision.DUPLICATE,
                AiRunProgressStore.Decision.STALE, AiRunProgressStore.Decision.INACTIVE)) {
            store.next = new AiRunProgressStore.Result(decision, null);
            callProgress(PLAN_HASH, 1, "RETRIEVING", token(PLAN_HASH, runId, clock.instant().plusSeconds(60)))
                    .andExpect(status().isOk());
        }
        assertThat(eventSink.stages).isEmpty();
    }

    @Test
    void conflictingOrdinalAndPlanMismatchReturnConflict() throws Exception {
        store.next = new AiRunProgressStore.Result(AiRunProgressStore.Decision.CONFLICT, null);
        callProgress(PLAN_HASH, 1, "RETRIEVING", token(PLAN_HASH, runId, clock.instant().plusSeconds(60)))
                .andExpect(status().isConflict());

        store.bound = Optional.of(OTHER_PLAN_HASH);
        callProgress(PLAN_HASH, 1, "RETRIEVING", token(PLAN_HASH, runId, clock.instant().plusSeconds(60)))
                .andExpect(status().isConflict());
        assertThat(eventSink.stages).isEmpty();
    }

    @Test
    void unboundRunIsNotFoundAndNothingIsRecorded() throws Exception {
        store.bound = Optional.empty();
        callProgress(PLAN_HASH, 1, "RETRIEVING", token(PLAN_HASH, runId, clock.instant().plusSeconds(60)))
                .andExpect(status().isNotFound());
        assertThat(store.recorded).isEmpty();
    }

    @Test
    void invalidOrExpiredTokenIsUnauthorizedBeforeRecording() throws Exception {
        callProgress(PLAN_HASH, 1, "RETRIEVING", token(PLAN_HASH, runId, clock.instant().minusSeconds(10)))
                .andExpect(status().isUnauthorized());
        callProgress(PLAN_HASH, 1, "RETRIEVING", token(OTHER_PLAN_HASH, runId, clock.instant().plusSeconds(60)))
                .andExpect(status().isUnauthorized());
        assertThat(store.recorded).isEmpty();
        assertThat(eventSink.stages).isEmpty();
    }

    @Test
    void rejectsUnknownStageSchemaRunMismatchAndZeroOrdinal() throws Exception {
        var valid = token(PLAN_HASH, runId, clock.instant().plusSeconds(60));
        callProgress(PLAN_HASH, 1, "UNKNOWN_STAGE", valid).andExpect(status().isBadRequest());
        callProgress(PLAN_HASH, 0, "RETRIEVING", valid).andExpect(status().isBadRequest());
        callProgress(PLAN_HASH, 1, "RETRIEVING", valid, "internal.ai.progress.v2", runId).andExpect(status().isBadRequest());
        callProgress(PLAN_HASH, 1, "RETRIEVING", valid, "internal.ai.progress.v1", UUID.randomUUID()).andExpect(status().isBadRequest());
        assertThat(store.recorded).isEmpty();
    }

    private ResultActions callProgress(String planHash, int ordinal, String stage, String token) throws Exception {
        return callProgress(planHash, ordinal, stage, token, "internal.ai.progress.v1", runId);
    }

    private ResultActions callProgress(String planHash, int ordinal, String stage, String token,
                               String schemaVersion, UUID bodyRunId) throws Exception {
        var payload = new InternalAiProgressPayload(schemaVersion, bodyRunId, planHash, ordinal, stage);
        return mockMvc.perform(post("/internal/v1/ai/runs/" + runId + "/progress")
                .header("X-Run-Progress-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)));
    }

    private String token(String planHash, UUID tokenRunId, Instant expiry) throws Exception {
        var b64 = Base64.getUrlEncoder().withoutPadding();
        var header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        var claimsJson = objectMapper.writeValueAsString(Map.of(
                "aud", HmacProgressCapabilityIssuer.AUDIENCE,
                "scope", HmacProgressCapabilityIssuer.SCOPE,
                "runId", tokenRunId.toString(),
                "planHash", planHash,
                "exp", expiry.getEpochSecond()));
        var payload = b64.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
        var signingInput = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return signingInput + "." + b64.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
    }

    record Recorded(UUID runId, String planHash, int ordinal, JourneyRunStage stage) { }

    static final class FakeProgressStore implements AiRunProgressStore {
        Optional<String> bound = Optional.empty();
        Result next = new Result(Decision.APPLIED, null);
        final List<Recorded> recorded = new ArrayList<>();

        @Override public Optional<String> boundPlanHash(UUID runId) { return bound; }

        @Override public Result record(UUID runId, String planHash, int ordinal, JourneyRunStage stage) {
            recorded.add(new Recorded(runId, planHash, ordinal, stage));
            return next;
        }
    }

    static final class TestEventSink implements JourneyRunEventSink {
        final List<String> stages = new ArrayList<>();

        @Override public JourneyRunEvent stage(UUID runId, String status, String stage) {
            stages.add(stage);
            return null;
        }

        @Override public JourneyRunEvent terminal(UUID runId, String status, String outcome) { return null; }
    }
}

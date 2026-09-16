package com.yrootlab.onmaru.web.exploration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.identity.guest.GuestCredentialService;
import com.yrootlab.onmaru.identity.guest.GuestGrantClaimCommand;
import com.yrootlab.onmaru.identity.guest.GuestGrantService;
import com.yrootlab.onmaru.journey.exploration.InMemoryExplorationRunDispatcher;
import com.yrootlab.onmaru.journey.exploration.InMemoryExplorationStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class ExplorationWebBoundaryTests {

    private static final Cookie CSRF_COOKIE = new Cookie("__Host-onmaru-csrf", "csrf-token");

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private InMemoryExplorationStore explorationStore;

    @Autowired
    private InMemoryExplorationRunDispatcher runDispatcher;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private Clock clock;

    @Autowired
    private GuestCredentialService guestCredentialService;

    @Autowired
    private GuestGrantService guestGrantService;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    private String guestToken;
    private String ownerToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        explorationStore.clear();
        runDispatcher.clear();
        identityStore.clear();
        guestCredentialService.clear();
        guestToken = guestCredentialService.issue().rawToken();
        ownerToken = guestCredentialService.issue().rawToken();
        otherToken = guestCredentialService.issue().rawToken();
    }

    @Test
    void guestMissingRegionReturnsTerminalClarificationWithoutAiDispatch() throws Exception {
        var response = mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "query", "조용한 한옥 여행을 하고 싶어요",
                                "locale", "ko-KR")))
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.explorationId", not(emptyOrNullString())))
                .andExpect(jsonPath("$.runId", not(emptyOrNullString())))
                .andExpect(jsonPath("$.snapshotUrl", not(emptyOrNullString())))
                .andReturn().getResponse().getContentAsByteArray();

        var explorationId = objectMapper.readTree(response).path("explorationId").asText();
        mockMvc.perform(get("/api/v1/explorations/{id}", explorationId).cookie(guestCookie(guestToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestRun.status").value("COMPLETED"))
                .andExpect(jsonPath("$.latestRun.outcome").value("CLARIFICATION_REQUIRED"))
                .andExpect(jsonPath("$.latestRun.clarification.id").value("region"))
                .andExpect(jsonPath("$.latestRun.clarification.reason").value("REGION_MISSING"));

        assertThat(runDispatcher.dispatchCount()).isZero();
        assertThat(explorationStore.explorationCount()).isEqualTo(1);
    }

    @Test
    void sameGuestCanReadButAnotherActorReceivesNotFound() throws Exception {
        var result = createGuestExploration(ownerToken, "kr-45-jeonju");
        var explorationId = objectMapper.readTree(result).path("explorationId").asText();

        mockMvc.perform(get("/api/v1/explorations/{id}", explorationId)
                        .cookie(guestCookie(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.explorationId").value(explorationId));

        mockMvc.perform(get("/api/v1/explorations/{id}", explorationId)
                        .cookie(guestCookie(otherToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void memberCanCreateAndReadOwnExploration() throws Exception {
        var memberId = identityStore.createMember(clock.instant());
        identityStore.saveSession(new SessionRecord(
                hasher.hash("member-session"),
                memberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600)));

        var response = mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "query", "서울의 한옥을 보고 싶어요",
                                "locale", "ko-KR",
                                "regionCode", "kr-11-seoul")))
                        .cookie(new Cookie("__Host-onmaru-session", "member-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId", not(emptyOrNullString())))
                .andReturn().getResponse().getContentAsByteArray();

        var explorationId = objectMapper.readTree(response).path("explorationId").asText();
        mockMvc.perform(get("/api/v1/explorations/{id}", explorationId)
                        .cookie(new Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explorationId").value(explorationId))
                .andExpect(jsonPath("$.latestRun.status").value("QUEUED"));
        assertThat(runDispatcher.dispatchCount()).isEqualTo(1);
    }

    @Test
    void memberWithGuestGrantCanReadGuestExploration() throws Exception {
        var created = createGuestExploration(ownerToken, null);
        var explorationId = UUID.fromString(objectMapper.readTree(created).path("explorationId").asText());
        var memberId = identityStore.createMember(clock.instant());
        identityStore.saveSession(new SessionRecord(
                hasher.hash("granted-member-session"),
                memberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600)));
        guestGrantService.claim(new GuestGrantClaimCommand(memberId, explorationId, ownerToken));

        mockMvc.perform(get("/api/v1/explorations/{id}", explorationId)
                        .cookie(new Cookie("__Host-onmaru-session", "granted-member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explorationId").value(explorationId.toString()));
    }

    @Test
    void rejectedCreateDoesNotPersistExplorationOrTurn() throws Exception {
        mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("query", " ", "locale", "ko-KR")))
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value("query"));

        assertThat(explorationStore.explorationCount()).isZero();
        assertThat(explorationStore.totalTurnCount()).isZero();
    }

    @Test
    void clarificationTurnPersistsOnceAndDispatchesOneRun() throws Exception {
        var created = createGuestExploration(ownerToken, null);
        var explorationId = objectMapper.readTree(created).path("explorationId").asText();
        var clientTurnId = UUID.randomUUID();
        var answer = new java.util.LinkedHashMap<String, Object>();
        answer.put("clarificationId", "region");
        answer.put("choiceId", null);
        answer.put("text", "전주");
        var turnBody = new java.util.LinkedHashMap<String, Object>();
        turnBody.put("clientTurnId", clientTurnId);
        turnBody.put("baseVersion", 0);
        turnBody.put("query", "전주로 갈게요");
        turnBody.put("clarificationAnswer", answer);
        var body = objectMapper.writeValueAsBytes(turnBody);

        for (var index = 0; index < 2; index++) {
            mockMvc.perform(post("/api/v1/explorations/{id}/turns", explorationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .cookie(guestCookie(ownerToken), CSRF_COOKIE)
                            .header("X-CSRF-TOKEN", "csrf-token")
                            .header("Idempotency-Key", UUID.randomUUID()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.runId", not(emptyOrNullString())));
        }

        assertThat(explorationStore.turnCount(UUID.fromString(explorationId))).isEqualTo(2);
        assertThat(explorationStore.storedQuery(UUID.fromString(explorationId), clientTurnId))
                .contains("전주로 갈게요");
        assertThat(runDispatcher.dispatchCount()).isEqualTo(1);
    }

    @Test
    void clarificationAnswerIsRejectedWhenLatestRunIsNotPendingClarification() throws Exception {
        var created = createGuestExploration(ownerToken, "kr-45-jeonju");
        var explorationId = objectMapper.readTree(created).path("explorationId").asText();
        var answer = Map.of(
                "clarificationId", "region",
                "text", "서울");

        mockMvc.perform(post("/api/v1/explorations/{id}/turns", explorationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "clientTurnId", UUID.randomUUID(),
                                "baseVersion", 0,
                                "query", "서울로 바꿀게요",
                                "clarificationAnswer", answer)))
                        .cookie(guestCookie(ownerToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        assertThat(explorationStore.turnCount(UUID.fromString(explorationId))).isEqualTo(1);
        assertThat(runDispatcher.dispatchCount()).isEqualTo(1);
    }

    @Test
    void unknownClarificationIdIsRejectedAsVersionConflict() throws Exception {
        var created = createGuestExploration(ownerToken, null);
        var explorationId = objectMapper.readTree(created).path("explorationId").asText();

        mockMvc.perform(post("/api/v1/explorations/{id}/turns", explorationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "clientTurnId", UUID.randomUUID(),
                                "baseVersion", 0,
                                "query", "전주로 갈게요",
                                "clarificationAnswer", Map.of(
                                        "clarificationId", "stale-region",
                                        "text", "전주"))))
                        .cookie(guestCookie(ownerToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        assertThat(explorationStore.turnCount(UUID.fromString(explorationId))).isEqualTo(1);
        assertThat(runDispatcher.dispatchCount()).isZero();
    }

    @Test
    void missingOrUnknownGuestCredentialReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/explorations/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/explorations/{id}", UUID.randomUUID())
                        .cookie(guestCookie("caller-invented-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void csrfBootstrapIssuesServerRegisteredGuestCredential() throws Exception {
        var response = mockMvc.perform(get("/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(value -> value.startsWith("__Host-onmaru-guest="))
                .anyMatch(value -> value.contains("Secure") && value.contains("HttpOnly") && value.contains("SameSite=Lax"));
    }

    @Test
    void safetyPrivacyAndScopeRejectionsDoNotPersistOrDispatch() throws Exception {
        var rejected = Map.of(
                "010-1234-5678로 연락 가능한 한옥 숙소", "PRIVACY_REDACT_REQUIRED",
                "<script>alert(1)</script> 전주 한옥", "SAFETY_BLOCKED",
                "조선 시대 역사 시험 답안을 대신 써줘", "JOURNEY_SCOPE_UNSUPPORTED");

        for (var entry : rejected.entrySet()) {
            mockMvc.perform(post("/api/v1/explorations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(Map.of(
                                    "query", entry.getKey(),
                                    "locale", "ko-KR",
                                    "regionCode", "kr-45-jeonju")))
                            .cookie(guestCookie(guestToken), CSRF_COOKIE)
                            .header("X-CSRF-TOKEN", "csrf-token")
                            .header("Idempotency-Key", UUID.randomUUID()))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value(entry.getValue()));
        }

        assertThat(explorationStore.explorationCount()).isZero();
        assertThat(explorationStore.totalTurnCount()).isZero();
        assertThat(runDispatcher.dispatchCount()).isZero();
    }

    @Test
    void controlCharactersAreRejectedBeforePersistenceOrDispatch() throws Exception {
        mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "query", "전주\u0000 한옥 여행",
                                "locale", "ko-KR",
                                "regionCode", "kr-45-jeonju")))
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAFETY_BLOCKED"));

        assertThat(explorationStore.explorationCount()).isZero();
        assertThat(explorationStore.totalTurnCount()).isZero();
        assertThat(runDispatcher.dispatchCount()).isZero();
    }

    @Test
    void unknownRequestFieldsAreStrictlyRejected() throws Exception {
        mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "query", "전주 한옥 여행",
                                "locale", "ko-KR",
                                "unexpected", true)))
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        var created = createGuestExploration(ownerToken, null);
        var explorationId = objectMapper.readTree(created).path("explorationId").asText();
        var turn = new java.util.LinkedHashMap<String, Object>();
        turn.put("clientTurnId", UUID.randomUUID());
        turn.put("baseVersion", 0);
        turn.put("query", "전주로 갈게요");
        turn.put("unexpected", true);

        mockMvc.perform(post("/api/v1/explorations/{id}/turns", explorationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(turn))
                        .cookie(guestCookie(ownerToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        turn.remove("unexpected");
        turn.put("clarificationAnswer", Map.of(
                "clarificationId", "region",
                "text", "전주",
                "unexpected", true));
        mockMvc.perform(post("/api/v1/explorations/{id}/turns", explorationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(turn))
                        .cookie(guestCookie(ownerToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(explorationStore.turnCount(UUID.fromString(explorationId))).isEqualTo(1);
        assertThat(runDispatcher.dispatchCount()).isZero();
    }

    @Test
    void createUsesIdempotencyKeyForReplayAndConflict() throws Exception {
        var key = UUID.randomUUID();
        var body = objectMapper.writeValueAsBytes(Map.of(
                "query", "전주 한옥 여행",
                "locale", "ko-KR",
                "regionCode", "kr-45-jeonju"));
        var first = createWithKey(body, key);
        var replay = createWithKey(body, key);

        assertThat(objectMapper.readTree(replay).path("explorationId").asText())
                .isEqualTo(objectMapper.readTree(first).path("explorationId").asText());
        assertThat(explorationStore.explorationCount()).isEqualTo(1);
        assertThat(runDispatcher.dispatchCount()).isEqualTo(1);

        mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "query", "서울 한옥 여행",
                                "locale", "ko-KR",
                                "regionCode", "kr-11-seoul")))
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void turnRequiresExplicitBaseVersionBeforePersistence() throws Exception {
        var created = createGuestExploration(ownerToken, null);
        var explorationId = objectMapper.readTree(created).path("explorationId").asText();

        mockMvc.perform(post("/api/v1/explorations/{id}/turns", explorationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "clientTurnId", UUID.randomUUID(),
                                "query", "전주 한옥")))
                        .cookie(guestCookie(ownerToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(explorationStore.turnCount(UUID.fromString(explorationId))).isEqualTo(1);
    }

    @Test
    void rejectedTurnDoesNotPersistRawTurnOrDispatchRun() throws Exception {
        var created = createGuestExploration(ownerToken, null);
        var explorationId = UUID.fromString(objectMapper.readTree(created).path("explorationId").asText());
        var beforeTurns = explorationStore.turnCount(explorationId);

        mockMvc.perform(post("/api/v1/explorations/{id}/turns", explorationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "clientTurnId", UUID.randomUUID(),
                                "baseVersion", 0,
                                "query", " ")))
                        .cookie(guestCookie(ownerToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value("query"));

        assertThat(explorationStore.turnCount(explorationId)).isEqualTo(beforeTurns);
        assertThat(runDispatcher.dispatchCount()).isZero();
    }

    private byte[] createGuestExploration(String guestToken, String regionCode) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("query", "한옥 여행을 하고 싶어요");
        body.put("locale", "ko-KR");
        if (regionCode != null) {
            body.put("regionCode", regionCode);
        }
        return mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body))
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private byte[] createWithKey(byte[] body, UUID key) throws Exception {
        return mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private Cookie guestCookie(String token) {
        return new Cookie("__Host-onmaru-guest", token);
    }
}

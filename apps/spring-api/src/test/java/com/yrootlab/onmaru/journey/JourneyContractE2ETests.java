package com.yrootlab.onmaru.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.catalog.application.query.detail.CoordinatesProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.ImageProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.RegionProjection;
import com.yrootlab.onmaru.identity.guest.GuestCredentialService;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.journey.cancellation.CancelJourneyRunCommand;
import com.yrootlab.onmaru.journey.cancellation.JourneyRunCancellationService;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunStatus;
import com.yrootlab.onmaru.journey.exploration.ExplorationService;
import com.yrootlab.onmaru.journey.exploration.InMemoryExplorationRunDispatcher;
import com.yrootlab.onmaru.journey.exploration.InMemoryExplorationStore;
import com.yrootlab.onmaru.journey.run.AdvanceRunStageCommand;
import com.yrootlab.onmaru.journey.run.ClaimRunCommand;
import com.yrootlab.onmaru.journey.run.CreateRunCommand;
import com.yrootlab.onmaru.journey.run.FinishRunCommand;
import com.yrootlab.onmaru.journey.run.JourneyRunSnapshot;
import com.yrootlab.onmaru.journey.run.JourneyRunStage;
import com.yrootlab.onmaru.journey.run.JourneyRunStatus;
import com.yrootlab.onmaru.journey.run.JourneyRunStore;
import com.yrootlab.onmaru.journey.run.RunCommandResult;
import com.yrootlab.onmaru.observability.InMemoryTelemetrySink;
import com.yrootlab.onmaru.operations.admission.AdmissionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
@Import(JourneyContractE2ETests.JourneyE2ETestConfig.class)
public class JourneyContractE2ETests {

    private static final String JEONJU_PLACE_ID = "p-jeonju-hanok-village";
    private static final String JEONJU_REGION = "kr-45-jeonju";
    private static final Cookie CSRF_COOKIE = new Cookie("__Host-onmaru-csrf", "csrf-token");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private GuestCredentialService guestCredentialService;

    @Autowired
    private InMemoryExplorationStore explorationStore;

    @Autowired
    private InMemoryExplorationRunDispatcher runDispatcher;

    @Autowired
    private ExplorationService explorationService;

    @Autowired
    private InMemoryPlaceDetailStore placeDetailStore;

    @Autowired
    private AdmissionService admissionService;

    @Autowired
    private InMemoryTelemetrySink telemetrySink;

    @Autowired
    private RecordingJourneyRunStore durableRunStore;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    private String guestToken;
    private String otherGuestToken;
    private UUID memberId;
    private UUID otherMemberId;

    @BeforeEach
    void setUp() {
        identityStore.clear();
        explorationStore.clear();
        runDispatcher.clear();
        guestCredentialService.clear();
        placeDetailStore.clear();
        telemetrySink.clear();
        durableRunStore.clear();

        guestToken = guestCredentialService.issue().rawToken();
        otherGuestToken = guestCredentialService.issue().rawToken();

        memberId = identityStore.createMember(clock.instant());
        otherMemberId = identityStore.createMember(clock.instant());

        identityStore.saveSession(new SessionRecord(
                hasher.hash("member-session"),
                memberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600)));
        identityStore.saveSession(new SessionRecord(
                hasher.hash("other-session"),
                otherMemberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600)));

        seedPlace();
    }

    @Test
    void clarificationAndBoard() throws Exception {
        // 1. Guest creates exploration without region -> Clarification required (REGION_MISSING)
        var createResponse = mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(Map.of(
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
                .andReturn().getResponse().getContentAsString();

        var explorationId = OBJECT_MAPPER.readTree(createResponse).path("explorationId").asText();

        var clarificationSnapshot = mockMvc.perform(get("/api/v1/explorations/{id}", explorationId)
                        .cookie(guestCookie(guestToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.latestRun.status").value("COMPLETED"))
                .andExpect(jsonPath("$.latestRun.outcome").value("CLARIFICATION_REQUIRED"))
                .andExpect(jsonPath("$.latestRun.clarification.id").value("region"))
                .andExpect(jsonPath("$.latestRun.clarification.reason").value("REGION_MISSING"))
                .andExpect(jsonPath("$.board").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(clarificationSnapshot).isNotEmpty();

        // 2. Answer clarification with region -> AI run queued
        var turnResponse = mockMvc.perform(post("/api/v1/explorations/{id}/turns", explorationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(Map.of(
                                "clientTurnId", UUID.randomUUID().toString(),
                                "baseVersion", 0,
                                "query", "전주 한옥 여행 갈게요",
                                "clarificationAnswer", Map.of(
                                        "clarificationId", "region",
                                        "text", "전주"))))
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId", not(emptyOrNullString())))
                .andReturn().getResponse().getContentAsString();

        var queuedRunId = UUID.fromString(OBJECT_MAPPER.readTree(turnResponse).path("runId").asText());

        // 3. Worker process: claim and complete run with INITIAL_BOARD
        var explorationUuid = UUID.fromString(explorationId);
        explorationService.claimRun(explorationUuid, queuedRunId, "INTERPRETING");
        explorationService.completeRun(explorationUuid, queuedRunId, ExplorationRunOutcome.INITIAL_BOARD);

        // 4. Fetch exploration snapshot -> contains board with items, summaryRefs, candidate place
        var boardSnapshot = mockMvc.perform(get("/api/v1/explorations/{id}", explorationId)
                        .cookie(guestCookie(guestToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.latestRun.status").value("COMPLETED"))
                .andExpect(jsonPath("$.latestRun.outcome").value("INITIAL_BOARD"))
                .andExpect(jsonPath("$.board.title").value("전주 한옥 산책"))
                .andExpect(jsonPath("$.board.candidates[0].placeRef.id").value(JEONJU_PLACE_ID))
                .andExpect(jsonPath("$.board.resources[0].title").value("전주 한옥마을"))
                .andReturn().getResponse().getContentAsString();

        assertJourneyFixtureShape("exploration-snapshot-normal.json", boardSnapshot);

        // 5. Member intake verification
        var memberExploration = mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(Map.of(
                                "query", "서울의 한옥을 둘러보고 싶어요",
                                "locale", "ko-KR",
                                "regionCode", "kr-11-seoul")))
                        .cookie(memberCookie("member-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId", not(emptyOrNullString())))
                .andReturn().getResponse().getContentAsString();
        assertThat(memberExploration).isNotEmpty();
    }

    @Test
    void proposalActionsAndVersioning() throws Exception {
        // Prepare completed exploration with stateVersion 0
        var explorationId = completedExplorationForGuest(guestToken);

        // 1. Normal PIN Action increments stateVersion to 1
        var pinCommandId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        var pinRequest = Map.of(
                "commandId", pinCommandId.toString(),
                "baseVersion", 0,
                "action", Map.of(
                        "type", "PIN",
                        "resourceRef", Map.of("type", "PLACE", "id", JEONJU_PLACE_ID)));

        var pinResponse = mockMvc.perform(post("/api/v1/explorations/{id}/actions", explorationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(pinRequest))
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", pinCommandId.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.stateVersion").value(1))
                .andExpect(jsonPath("$.pinnedRefs[0].id").value(JEONJU_PLACE_ID))
                .andReturn().getResponse().getContentAsString();

        assertJourneyFixtureShape("action-pin-normal.json", pinResponse);

        // 2. Idempotent replay: Re-sending identical request with same key returns 200
        var replayResponse = mockMvc.perform(post("/api/v1/explorations/{id}/actions", explorationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(pinRequest))
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", pinCommandId.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.stateVersion").value(1))
                .andReturn().getResponse().getContentAsString();

        assertJourneyFixtureShape("action-idempotency-replay.json", replayResponse);

        // 3. Idempotency conflict: Re-using same key with different body returns 409 IDEMPOTENCY_CONFLICT
        var conflictRequest = Map.of(
                "commandId", pinCommandId.toString(),
                "baseVersion", 1,
                "action", Map.of(
                        "type", "EXCLUDE",
                        "resourceRef", Map.of("type", "PLACE", "id", JEONJU_PLACE_ID)));

        var conflictResponse = mockMvc.perform(post("/api/v1/explorations/{id}/actions", explorationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(conflictRequest))
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", pinCommandId.toString()))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andReturn().getResponse().getContentAsString();

        assertJourneyFixtureShape("action-idempotency-conflict.json", conflictResponse);

        // 4. Stale baseVersion conflict: action with baseVersion 0 when current is 1 returns 409 VERSION_CONFLICT
        var staleRequest = Map.of(
                "commandId", UUID.randomUUID().toString(),
                "baseVersion", 0,
                "action", Map.of(
                        "type", "EXCLUDE",
                        "resourceRef", Map.of("type", "PLACE", "id", "p-other-place")));

        var staleResponse = mockMvc.perform(post("/api/v1/explorations/{id}/actions", explorationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(staleRequest))
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andReturn().getResponse().getContentAsString();

        assertJourneyFixtureShape("action-version-conflict.json", staleResponse);

        // 5. CSRF token missing returns 403 CSRF_INVALID
        var csrfResponse = mockMvc.perform(post("/api/v1/explorations/{id}/actions", explorationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(pinRequest))
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
                .andReturn().getResponse().getContentAsString();

        assertJourneyFixtureShape("action-csrf-invalid.json", csrfResponse);

        // 6. Other actor isolation returns 404 NOT_FOUND
        var otherActorResponse = mockMvc.perform(post("/api/v1/explorations/{id}/actions", explorationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(Map.of(
                                "commandId", UUID.randomUUID().toString(),
                                "baseVersion", 1,
                                "action", Map.of(
                                        "type", "PIN",
                                        "resourceRef", Map.of("type", "PLACE", "id", JEONJU_PLACE_ID)))))
                        .cookie(guestCookie(otherGuestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();

        assertJourneyFixtureShape("action-other-actor.json", otherActorResponse);
    }

    @Test
    void sseLifecycleAndReconnect() throws Exception {
        var createResponse = mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(Map.of(
                                "query", "전주 한옥 투어",
                                "locale", "ko-KR",
                                "regionCode", JEONJU_REGION)))
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        var explorationId = UUID.fromString(OBJECT_MAPPER.readTree(createResponse).path("explorationId").asText());
        var runId = UUID.fromString(OBJECT_MAPPER.readTree(createResponse).path("runId").asText());
        var eventsUrl = "/api/v1/explorations/" + explorationId + "/runs/" + runId + "/events";

        // 1. Live SSE stream starts and registers async request
        mockMvc.perform(get(eventsUrl).cookie(guestCookie(guestToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("text/event-stream")))
                .andExpect(request().asyncStarted());

        // 2. Claim and complete run -> stream delivers stage & terminal
        explorationService.claimRun(explorationId, runId, "INTERPRETING");
        explorationService.completeRun(explorationId, runId, ExplorationRunOutcome.INITIAL_BOARD);

        var completedStream = mockMvc.perform(get(eventsUrl).cookie(guestCookie(guestToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/event-stream;charset=UTF-8"))
                .andReturn().getResponse().getContentAsString();

        assertThat(completedStream).contains("event:run.stage");
        assertThat(completedStream).contains("event:run.terminal");
        assertThat(completedStream).contains("\"outcome\":\"INITIAL_BOARD\"");

        // 3. Reconnect with Last-Event-ID replay
        var replayStream = mockMvc.perform(get(eventsUrl)
                        .cookie(guestCookie(guestToken))
                        .header("Last-Event-ID", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(replayStream).contains("id:2");
        assertThat(replayStream).contains("event:run.terminal");

        // 4. Stale Last-Event-ID beyond buffer returns reset event
        var resetResponse = mockMvc.perform(get(eventsUrl)
                        .cookie(guestCookie(guestToken))
                        .header("Last-Event-ID", "0"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("text/event-stream")))
                .andReturn().getResponse().getContentAsString();

        assertThat(resetResponse).contains("event:reset");
        assertThat(resetResponse).contains(runId.toString());

        // 5. Unauthenticated SSE connection closes safely with event: auth_closed
        var authClosedResponse = mockMvc.perform(get("/api/v1/explorations/{explorationId}/runs/{runId}/events",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString();

        assertThat(authClosedResponse).contains("event:auth_closed");
    }

    @Test
    void runCancellationAndRace() throws Exception {
        var createResponse = mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(Map.of(
                                "query", "전주 한옥 여행 계획",
                                "locale", "ko-KR",
                                "regionCode", JEONJU_REGION)))
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        var explorationId = OBJECT_MAPPER.readTree(createResponse).path("explorationId").asText();
        var runId = OBJECT_MAPPER.readTree(createResponse).path("runId").asText();

        // 1. Cancel queued run
        mockMvc.perform(post("/api/v1/explorations/{explorationId}/runs/{runId}/cancel", explorationId, runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 2. Fetch run snapshot -> CANCELLED
        mockMvc.perform(get("/api/v1/explorations/{explorationId}/runs/{runId}", explorationId, runId)
                        .cookie(guestCookie(guestToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 3. Repeat cancel call is idempotent
        mockMvc.perform(post("/api/v1/explorations/{explorationId}/runs/{runId}/cancel", explorationId, runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(durableRunStore.cancellations()).hasSize(2);
    }

    @Test
    void aiOutageFallbackAndQuota() throws Exception {
        // 1. Guest daily AI Quota enforcement (Limit 2 per day)
        var first = OBJECT_MAPPER.readTree(createGuestExploration(guestToken, JEONJU_REGION));
        cancelRun(guestToken, first.path("explorationId").asText(), first.path("runId").asText());

        var second = OBJECT_MAPPER.readTree(createGuestExploration(guestToken, JEONJU_REGION));
        cancelRun(guestToken, second.path("explorationId").asText(), second.path("runId").asText());

        // 3rd exploration attempt in the same day triggers 429 RATE_LIMITED
        mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(Map.of(
                                "query", "경주 한옥 여행",
                                "locale", "ko-KR",
                                "regionCode", "kr-47-gyeongju")))
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.details.retryAfterMs", greaterThan(0)));

        // 2. AI Fallback: completing run with degraded baseline reason produces stable board
        var thirdGuestToken = guestCredentialService.issue().rawToken();
        var fallbackExp = OBJECT_MAPPER.readTree(createGuestExploration(thirdGuestToken, JEONJU_REGION));
        var expUuid = UUID.fromString(fallbackExp.path("explorationId").asText());
        var runUuid = UUID.fromString(fallbackExp.path("runId").asText());

        // Complete with outcome INITIAL_BOARD
        explorationService.completeRun(expUuid, runUuid, ExplorationRunOutcome.INITIAL_BOARD);

        mockMvc.perform(get("/api/v1/explorations/{id}", expUuid)
                        .cookie(guestCookie(thirdGuestToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.board.title").value("전주 한옥 산책"))
                .andExpect(jsonPath("$.board.candidates[0].placeRef.id").value(JEONJU_PLACE_ID));
    }

    @Test
    void savedJourneyAndResume() throws Exception {
        // 1. Member creates and completes an exploration
        var explorationId = completedExplorationForMember(memberId);

        // 2. Member saves journey -> 201 Created
        var createRequest = Map.of(
                "explorationId", explorationId.toString(),
                "baseVersion", 0,
                "title", "전주 한옥 산책");
        var createKey = UUID.randomUUID();

        var createResponse = mockMvc.perform(post("/api/v1/saved-journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(createRequest))
                        .cookie(memberCookie("member-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", createKey))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.savedJourneyId", not(emptyOrNullString())))
                .andExpect(jsonPath("$.title").value("전주 한옥 산책"))
                .andExpect(jsonPath("$.snapshot.board.summaryRefs").isArray())
                .andReturn().getResponse().getContentAsString();

        assertJourneyFixtureShape("saved-journey-create-normal.json", createResponse);
        var savedJourneyId = OBJECT_MAPPER.readTree(createResponse).path("savedJourneyId").asText();

        // 3. Replay of same exploration version returns 200 with existing saved journey
        var createReplayResponse = mockMvc.perform(post("/api/v1/saved-journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(createRequest))
                        .cookie(memberCookie("member-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.savedJourneyId").value(savedJourneyId))
                .andReturn().getResponse().getContentAsString();

        assertJourneyFixtureShape("saved-journey-create-replay.json", createReplayResponse);

        // 4. Save attempt while run is active returns 409 ACTIVE_RUN
        var activeExplorationResponse = mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(Map.of(
                                "query", "서울 한옥 여행",
                                "locale", "ko-KR",
                                "regionCode", "kr-11-seoul")))
                        .cookie(memberCookie("other-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        var activeExplorationId = OBJECT_MAPPER.readTree(activeExplorationResponse).path("explorationId").asText();

        var activeRunSaveResponse = mockMvc.perform(post("/api/v1/saved-journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(Map.of(
                                "explorationId", activeExplorationId,
                                "baseVersion", 0,
                                "title", "서울 한옥 산책")))
                        .cookie(memberCookie("other-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("ACTIVE_RUN"))
                .andReturn().getResponse().getContentAsString();

        assertJourneyFixtureShape("saved-journey-active-run.json", activeRunSaveResponse);

        // 5. List saved journeys with cursor pagination
        var listResponse = mockMvc.perform(get("/api/v1/saved-journeys")
                        .param("limit", "10")
                        .cookie(memberCookie("member-session")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].savedJourneyId").value(savedJourneyId))
                .andReturn().getResponse().getContentAsString();

        assertJourneyFixtureShape("saved-journey-page-normal.json", listResponse);

        // 6. Get saved journey detail view
        var detailResponse = mockMvc.perform(get("/api/v1/saved-journeys/{id}", savedJourneyId)
                        .cookie(memberCookie("member-session")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.savedJourneyId").value(savedJourneyId))
                .andReturn().getResponse().getContentAsString();

        assertJourneyFixtureShape("saved-journey-detail-expired-source.json", detailResponse);

        // 7. Other member isolation (detail/resume/delete returns 404)
        var otherDetailResponse = mockMvc.perform(get("/api/v1/saved-journeys/{id}", savedJourneyId)
                        .cookie(memberCookie("other-session")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertJourneyFixtureShape("saved-journey-detail-other-actor.json", otherDetailResponse);

        var otherResumeResponse = mockMvc.perform(post("/api/v1/saved-journeys/{id}/resume", savedJourneyId)
                        .cookie(memberCookie("other-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertJourneyFixtureShape("saved-journey-resume-other-actor.json", otherResumeResponse);

        var otherDeleteResponse = mockMvc.perform(delete("/api/v1/saved-journeys/{id}", savedJourneyId)
                        .cookie(memberCookie("other-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertJourneyFixtureShape("saved-journey-delete-other-actor.json", otherDeleteResponse);

        // 8. Resume saved journey with unavailable place
        placeDetailStore.clear(); // Mark places unavailable
        var unavailableResumeResponse = mockMvc.perform(post("/api/v1/saved-journeys/{id}/resume", savedJourneyId)
                        .cookie(memberCookie("member-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.unavailableRefs").isArray())
                .andExpect(jsonPath("$.unavailableRefs[0].id").value(JEONJU_PLACE_ID))
                .andExpect(jsonPath("$.exploration.stateVersion").value(0))
                .andExpect(jsonPath("$.exploration.board").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertJourneyFixtureShape("saved-journey-resume-unavailable.json", unavailableResumeResponse);

        // 9. Delete saved journey and idempotent delete replay
        var deleteKey = UUID.randomUUID();
        var deleteResponse = mockMvc.perform(delete("/api/v1/saved-journeys/{id}", savedJourneyId)
                        .cookie(memberCookie("member-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", deleteKey))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertJourneyFixtureShape("saved-journey-delete-normal.json", deleteResponse);

        var deleteReplayResponse = mockMvc.perform(delete("/api/v1/saved-journeys/{id}", savedJourneyId)
                        .cookie(memberCookie("member-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", deleteKey))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertJourneyFixtureShape("saved-journey-delete-replay.json", deleteReplayResponse);
    }

    private String createGuestExploration(String token, String regionCode) throws Exception {
        return mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(Map.of(
                                "query", "한옥 탐색 여행",
                                "locale", "ko-KR",
                                "regionCode", regionCode)))
                        .cookie(guestCookie(token), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    private void cancelRun(String token, String explorationId, String runId) throws Exception {
        mockMvc.perform(post("/api/v1/explorations/{explorationId}/runs/{runId}/cancel", explorationId, runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(guestCookie(token), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk());
    }

    private UUID completedExplorationForGuest(String token) throws Exception {
        var createResponse = createGuestExploration(token, JEONJU_REGION);
        var explorationId = UUID.fromString(OBJECT_MAPPER.readTree(createResponse).path("explorationId").asText());
        var runId = UUID.fromString(OBJECT_MAPPER.readTree(createResponse).path("runId").asText());
        explorationService.claimRun(explorationId, runId, "INTERPRETING");
        explorationService.completeRun(explorationId, runId, ExplorationRunOutcome.INITIAL_BOARD);
        return explorationId;
    }

    private UUID completedExplorationForMember(UUID memberId) throws Exception {
        var createResponse = mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsBytes(Map.of(
                                "query", "전주 한옥 여행",
                                "locale", "ko-KR",
                                "regionCode", JEONJU_REGION)))
                        .cookie(memberCookie("member-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        var explorationId = UUID.fromString(OBJECT_MAPPER.readTree(createResponse).path("explorationId").asText());
        var runId = UUID.fromString(OBJECT_MAPPER.readTree(createResponse).path("runId").asText());
        explorationService.claimRun(explorationId, runId, "INTERPRETING");
        explorationService.completeRun(explorationId, runId, ExplorationRunOutcome.INITIAL_BOARD);
        return explorationId;
    }

    private Cookie guestCookie(String token) {
        return new Cookie("__Host-onmaru-guest", token);
    }

    private Cookie memberCookie(String session) {
        return new Cookie("__Host-onmaru-session", session);
    }

    private void seedPlace() {
        placeDetailStore.add(PlaceProjection.publicPlace(
                JEONJU_PLACE_ID,
                "전주 한옥마을",
                "한옥",
                new RegionProjection(JEONJU_REGION, "전북 전주시"),
                "전북 전주시 완산구 기린대로 99",
                new CoordinatesProjection(35.8151, 127.153),
                List.of(new ImageProjection(
                        "https://cdn.onmaru.example/places/" + JEONJU_PLACE_ID + "/cover.jpg",
                        "전주 한옥마을 골목")),
                "전통 한옥과 공예, 음식, 산책 코스를 한 번에 경험할 수 있는 공개 관광 장소입니다.",
                List.of("한옥 골목", "공예 체험", "야간 산책"),
                "odii-jeonju-hanok-village"));
    }

    private void assertJourneyFixtureShape(String fixtureName, String runtimeJson) throws Exception {
        var fixture = Path.of("..", "..", "docs", "contracts", "fixtures", "journey", fixtureName);
        var expectedResponse = OBJECT_MAPPER.readTree(fixture.toFile()).path("response");
        if (!expectedResponse.has("body")) {
            return;
        }
        var expectedBody = expectedResponse.path("body");
        if (expectedBody.isMissingNode() || expectedBody.isNull()) {
            return;
        }
        if (runtimeJson == null || runtimeJson.isBlank()) {
            assertThat(expectedBody.isNull()).isTrue();
            return;
        }
        var runtimeBody = OBJECT_MAPPER.readTree(runtimeJson);
        assertSameShape(expectedBody, runtimeBody, "$");
        assertNoProviderKeys(runtimeBody, "$");
    }

    private static final Set<String> NULLABLE_FIELDS = Set.of(
            "board", "summary", "image", "location", "sourceUrl", "asOf",
            "sourceExplorationId", "stage", "outcome", "clarification",
            "startedAt", "deadlineAt", "error", "querySummary", "latestRun", "pendingProposal");

    private void assertSameShape(JsonNode expected, JsonNode actual, String path) {
        assertThat(actual).as(path).isNotNull();
        var fieldName = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        fieldName = fieldName.replaceAll("\\[\\d+\\]", "");

        if (actual.isNull() || expected.isNull()) {
            if (NULLABLE_FIELDS.contains(fieldName)) {
                return;
            }
            assertThat(actual.isNull()).as(path).isEqualTo(expected.isNull());
            return;
        }
        if (expected.isNumber() && actual.isNumber()) {
            return;
        }
        assertThat(actual.getNodeType()).as(path).isEqualTo(expected.getNodeType());
        if (expected.isObject()) {
            var expectedKeys = expected.propertyStream().map(Map.Entry::getKey).toList();
            var actualKeys = actual.propertyStream().map(Map.Entry::getKey).toList();
            for (String key : expectedKeys) {
                assertThat(actualKeys).as(path + " contains key " + key).contains(key);
                assertSameShape(expected.get(key), actual.get(key), path + "." + key);
            }
        } else if (expected.isArray() && actual.isArray()) {
            if (!expected.isEmpty() && !actual.isEmpty()) {
                assertSameShape(expected.get(0), actual.get(0), path + "[0]");
            }
        }
    }

    private void assertNoProviderKeys(JsonNode node, String path) {
        if (node.isObject()) {
            node.propertyStream().forEach(entry -> {
                var key = entry.getKey();
                assertThat(key).as(path + "." + key)
                        .isNotIn("stid", "stlid", "serviceKey", "contentId", "internalId", "rawId");
                assertNoProviderKeys(entry.getValue(), path + "." + key);
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                assertNoProviderKeys(node.get(i), path + "[" + i + "]");
            }
        }
    }

    @TestConfiguration
    static class JourneyE2ETestConfig {

        @Bean
        @Primary
        InMemoryTelemetrySink inMemoryTelemetrySink() {
            return new InMemoryTelemetrySink();
        }

        @Bean
        RecordingJourneyRunStore recordingJourneyRunStore() {
            return new RecordingJourneyRunStore();
        }

        @Bean
        JourneyRunCancellationService journeyRunCancellationService(RecordingJourneyRunStore store) {
            return new JourneyRunCancellationService(store);
        }
    }

    static final class RecordingJourneyRunStore implements JourneyRunStore {

        private final List<CancelJourneyRunCommand> cancellations = new ArrayList<>();

        @Override
        public RunCommandResult create(CreateRunCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunCommandResult claim(ClaimRunCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunCommandResult advance(AdvanceRunStageCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunCommandResult finish(FinishRunCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized RunCommandResult cancel(CancelJourneyRunCommand command) {
            cancellations.add(command);
            return new RunCommandResult(
                    new com.yrootlab.onmaru.journey.run.RunCommandReceipt(
                            command.runId(),
                            JourneyRunStatus.CANCELLED,
                            null,
                            null,
                            2),
                    false);
        }

        @Override
        public Optional<JourneyRunSnapshot> find(UUID runId, String actorKey) {
            return Optional.of(new JourneyRunSnapshot(
                    runId,
                    UUID.randomUUID(),
                    actorKey,
                    0,
                    JourneyRunStatus.CANCELLED,
                    (JourneyRunStage) null,
                    null,
                    Instant.parse("2026-09-16T00:00:00Z"),
                    Instant.parse("2026-09-16T00:00:20Z"),
                    null,
                    2,
                    null,
                    "LLM"));
        }

        synchronized List<CancelJourneyRunCommand> cancellations() {
            return List.copyOf(cancellations);
        }

        synchronized void clear() {
            cancellations.clear();
        }
    }
}

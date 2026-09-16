package com.yrootlab.onmaru.web.exploration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.catalog.application.query.detail.CoordinatesProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.ImageProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.RegionProjection;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.identity.guest.GuestCredentialService;
import com.yrootlab.onmaru.identity.guest.GuestGrantClaimCommand;
import com.yrootlab.onmaru.identity.guest.GuestGrantService;
import com.yrootlab.onmaru.journey.cancellation.CancelJourneyRunCommand;
import com.yrootlab.onmaru.journey.cancellation.JourneyRunCancellationService;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
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
import com.yrootlab.onmaru.operations.admission.AdmissionPolicy;
import com.yrootlab.onmaru.operations.admission.AdmissionRequest;
import com.yrootlab.onmaru.operations.admission.AdmissionService;
import com.yrootlab.onmaru.operations.admission.AdmissionSubject;
import com.yrootlab.onmaru.operations.admission.SubjectType;
import io.micrometer.core.instrument.MeterRegistry;
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

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
@Import(ExplorationWebBoundaryTests.TelemetryTestConfig.class)
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
    private ExplorationService explorationService;

    @Autowired
    private InMemoryJourneyRunEventStream runEventStream;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private Clock clock;

    @Autowired
    private GuestCredentialService guestCredentialService;

    @Autowired
    private GuestGrantService guestGrantService;

    @Autowired
    private AdmissionService admissionService;

    @Autowired
    private AdmissionPolicy admissionPolicy;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private InMemoryPlaceDetailStore placeDetailStore;

    @Autowired
    private InMemoryTelemetrySink telemetrySink;

    @Autowired
    private RecordingJourneyRunStore durableRunStore;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    private UUID guestId;
    private String guestToken;
    private String ownerToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        explorationStore.clear();
        runDispatcher.clear();
        runEventStream.clear();
        identityStore.clear();
        guestCredentialService.clear();
        durableRunStore.clear();
        var guest = guestCredentialService.issue();
        guestId = guest.guestId();
        guestToken = guest.rawToken();
        placeDetailStore.clear();
        telemetrySink.clear();
        seedPublicJeonjuPlace();
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
    void guestAiQuotaRejectsThirdDailyQueuedRunWithoutDispatching() throws Exception {
        var first = objectMapper.readTree(createGuestExploration(guestToken, "kr-45-jeonju"));
        cancelRun(guestToken, first.path("explorationId").asText(), first.path("runId").asText());
        var second = objectMapper.readTree(createGuestExploration(guestToken, "kr-11-seoul"));
        cancelRun(guestToken, second.path("explorationId").asText(), second.path("runId").asText());

        mockMvc.perform(post("/api/v1/explorations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
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

        assertThat(explorationStore.explorationCount()).isEqualTo(2);
        assertThat(runDispatcher.dispatchCount()).isEqualTo(2);
    }

    @Test
    void cancelRunReturnsActiveAdmissionSlotForSameGuest() throws Exception {
        var created = createGuestExploration(guestToken, "kr-45-jeonju");
        var root = objectMapper.readTree(created);
        var explorationId = root.path("explorationId").asText();
        var runId = root.path("runId").asText();
        var subject = new AdmissionRequest(
                "journey.ai",
                new AdmissionSubject(SubjectType.GUEST, guestId.toString()));
        var rejectedBefore = counter(
                "onmaru.admission.decisions",
                "operation", "journey.ai",
                "subjectType", "GUEST",
                "decision", "rejected",
                "reason", "ACTIVE_LIMIT");
        var releasedBefore = counter(
                "onmaru.admission.releases",
                "operation", "journey.ai",
                "subjectType", "GUEST");

        assertThat(admissionService.admitActive(subject, admissionPolicy).allowed()).isFalse();
        assertThat(counter(
                "onmaru.admission.decisions",
                "operation", "journey.ai",
                "subjectType", "GUEST",
                "decision", "rejected",
                "reason", "ACTIVE_LIMIT")).isEqualTo(rejectedBefore + 1.0);

        mockMvc.perform(post("/api/v1/explorations/{explorationId}/runs/{runId}/cancel", explorationId, runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(counter(
                "onmaru.admission.releases",
                "operation", "journey.ai",
                "subjectType", "GUEST")).isEqualTo(releasedBefore + 1.0);

        assertThat(admissionService.admitActive(subject, admissionPolicy).allowed()).isTrue();
        admissionService.releaseActive(subject, admissionPolicy);
    }

    @Test
    void cancelRunCancelsDurableRunForSameActorAndRun() throws Exception {
        var created = createGuestExploration(guestToken, "kr-45-jeonju");
        var root = objectMapper.readTree(created);
        var explorationId = root.path("explorationId").asText();
        var runId = root.path("runId").asText();

        mockMvc.perform(post("/api/v1/explorations/{explorationId}/runs/{runId}/cancel", explorationId, runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(durableRunStore.cancellations()).singleElement().satisfies(command -> {
            assertThat(command.actorKey()).isEqualTo("GUEST:" + guestId);
            assertThat(command.runId()).isEqualTo(UUID.fromString(runId));
            assertThat(command.requestHash()).isNotBlank();
            assertThat(command.cancelledAt()).isNotNull();
        });
    }

    @Test
    void ownerCanHydrateLatestRunSnapshotDirectly() throws Exception {
        var created = createGuestExploration(ownerToken, "kr-45-jeonju");
        var body = objectMapper.readTree(created);
        var explorationId = body.path("explorationId").asText();
        var runId = body.path("runId").asText();
        telemetrySink.clear();

        var result = mockMvc.perform(get("/api/v1/explorations/{explorationId}/runs/{runId}", explorationId, runId)
                        .cookie(guestCookie(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.engine").value("LLM"))
                .andExpect(jsonPath("$.outcome").isEmpty())
                .andExpect(jsonPath("$.clarification").isEmpty())
                .andExpect(jsonPath("$.retryAfterMs").value(0))
                .andExpect(jsonPath("$.deadlineAt", not(emptyOrNullString())))
                .andReturn();

        var requestId = result.getResponse().getHeader("X-Request-Id");
        assertThat(telemetrySink.events()).hasSize(1);
        assertThat(telemetrySink.events().getFirst().attributes())
                .containsEntry("request.id", requestId)
                .containsEntry("http.request.method", "GET")
                .containsEntry("http.route", "/api/v1/explorations/{explorationId}/runs/{runId}")
                .containsEntry("http.response.status_code", "200");
    }

    @Test
    void runSnapshotConcealsOtherActorAndUnknownRunAsNotFound() throws Exception {
        var created = createGuestExploration(ownerToken, "kr-45-jeonju");
        var body = objectMapper.readTree(created);
        var explorationId = body.path("explorationId").asText();
        var runId = body.path("runId").asText();

        mockMvc.perform(get("/api/v1/explorations/{explorationId}/runs/{runId}", explorationId, runId)
                        .cookie(guestCookie(otherToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/explorations/{explorationId}/runs/{runId}", explorationId, UUID.randomUUID())
                        .cookie(guestCookie(ownerToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void completedExplorationHydratesPublicBoardFromCurrentCatalog() throws Exception {
        var created = createGuestExploration(ownerToken, "kr-45-jeonju");
        var body = objectMapper.readTree(created);
        var explorationId = UUID.fromString(body.path("explorationId").asText());
        var runId = UUID.fromString(body.path("runId").asText());
        explorationService.claimRun(explorationId, runId, "INTERPRETING");
        explorationService.completeRun(explorationId, runId, ExplorationRunOutcome.INITIAL_BOARD);

        mockMvc.perform(get("/api/v1/explorations/{id}", explorationId)
                        .cookie(guestCookie(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.board.title").value("전주 한옥 산책"))
                .andExpect(jsonPath("$.board.candidates[0].placeRef.id").value("p-jeonju-hanok-village"))
                .andExpect(jsonPath("$.board.resources[0].title").value("전주 한옥마을"))
                .andExpect(jsonPath("$.execution.dataMode").value("PUBLIC"))
                .andExpect(jsonPath("$.execution.datasetRevision").value("catalog-current"))
                .andExpect(jsonPath("$.unavailableRefs").isEmpty());
    }

    @Test
    void unavailableCatalogPlaceIsReportedWithoutLeakingPrivateBoard() throws Exception {
        placeDetailStore.clear();
        placeDetailStore.add(PlaceProjection.hidden("p-jeonju-hanok-village"));
        var created = createGuestExploration(ownerToken, "kr-45-jeonju");
        var body = objectMapper.readTree(created);
        var explorationId = UUID.fromString(body.path("explorationId").asText());
        var runId = UUID.fromString(body.path("runId").asText());
        explorationService.claimRun(explorationId, runId, "INTERPRETING");
        explorationService.completeRun(explorationId, runId, ExplorationRunOutcome.INITIAL_BOARD);

        mockMvc.perform(get("/api/v1/explorations/{id}", explorationId)
                        .cookie(guestCookie(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.board").isEmpty())
                .andExpect(jsonPath("$.unavailableRefs[0].type").value("PLACE"))
                .andExpect(jsonPath("$.unavailableRefs[0].id").value("p-jeonju-hanok-village"));
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
    void turnUsesIdempotencyKeyForReplayAndConflict() throws Exception {
        var created = createGuestExploration(ownerToken, null);
        var explorationId = objectMapper.readTree(created).path("explorationId").asText();
        var key = UUID.randomUUID();
        var clientTurnId = UUID.randomUUID();
        var body = objectMapper.writeValueAsBytes(Map.of(
                "clientTurnId", clientTurnId,
                "baseVersion", 0,
                "query", "전주로 갈게요",
                "clarificationAnswer", Map.of(
                        "clarificationId", "region",
                        "text", "전주")));

        var first = createTurnWithKey(explorationId, body, key);
        var replay = createTurnWithKey(explorationId, body, key);

        assertThat(objectMapper.readTree(replay).path("runId").asText())
                .isEqualTo(objectMapper.readTree(first).path("runId").asText());
        assertThat(explorationStore.turnCount(UUID.fromString(explorationId))).isEqualTo(2);
        assertThat(runDispatcher.dispatchCount()).isEqualTo(1);

        mockMvc.perform(post("/api/v1/explorations/{id}/turns", explorationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "clientTurnId", clientTurnId,
                                "baseVersion", 0,
                                "query", "서울로 바꿀게요",
                                "clarificationAnswer", Map.of(
                                        "clarificationId", "region",
                                        "text", "서울"))))
                        .cookie(guestCookie(ownerToken), CSRF_COOKIE)
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

    @Test
    void runEventsReplaysStageAndTerminalFramesThenCloses() throws Exception {
        var created = createGuestExploration(ownerToken, "kr-45-jeonju");
        var tree = objectMapper.readTree(created);
        var explorationId = UUID.fromString(tree.path("explorationId").asText());
        var runId = UUID.fromString(tree.path("runId").asText());
        var eventsUrl = tree.path("eventsUrl").asText();

        explorationService.claimRun(explorationId, runId, "INTERPRETING");
        explorationService.completeRun(explorationId, runId, ExplorationRunOutcome.INITIAL_BOARD);

        var body = mockMvc.perform(get(eventsUrl).cookie(guestCookie(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/event-stream;charset=UTF-8"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("retry:15000");
        assertThat(body).contains("event:run.stage");
        assertThat(body).contains("\"schemaVersion\":\"1.2\"");
        assertThat(body).contains("\"runId\":\"" + runId + "\"");
        assertThat(body).contains("\"status\":\"QUEUED\"");
        assertThat(body).contains("\"status\":\"RUNNING\"");
        assertThat(body).contains("event:run.terminal");
        assertThat(body).contains("\"outcome\":\"INITIAL_BOARD\"");
        assertThat(body).doesNotContain("event:heartbeat");
        assertThat(telemetrySink.events()).anySatisfy(event -> assertThat(event.attributes())
                .containsEntry("http.route", "/api/v1/explorations/{explorationId}/runs/{runId}/events")
                .containsEntry("http.response.status_code", "200"));
        assertThat(telemetrySink.events()).anySatisfy(event -> {
            assertThat(event.name()).isEqualTo("journey.sse.replayed");
            assertThat(event.attributes())
                    .containsEntry("run.id", runId.toString())
                    .containsEntry("event.count", "3")
                    .containsEntry("reset", "false")
                    .containsEntry("terminal.close", "true");
        });
    }

    @Test
    void runEventsHonorsLastEventIdAndReportsReplayTelemetry() throws Exception {
        var created = createGuestExploration(ownerToken, "kr-45-jeonju");
        var tree = objectMapper.readTree(created);
        var explorationId = UUID.fromString(tree.path("explorationId").asText());
        var runId = UUID.fromString(tree.path("runId").asText());
        var eventsUrl = tree.path("eventsUrl").asText();

        explorationService.claimRun(explorationId, runId, "INTERPRETING");
        explorationService.completeRun(explorationId, runId, ExplorationRunOutcome.INITIAL_BOARD);

        var body = mockMvc.perform(get(eventsUrl)
                        .cookie(guestCookie(ownerToken))
                        .header("Last-Event-ID", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"status\":\"QUEUED\"");
        assertThat(body).contains("\"status\":\"RUNNING\"");
        assertThat(body).contains("event:run.terminal");
        assertThat(body).contains("id:2");
        assertThat(body).contains("id:3");
        assertThat(telemetrySink.events()).anySatisfy(event -> {
            assertThat(event.name()).isEqualTo("journey.sse.replayed");
            assertThat(event.attributes())
                    .containsEntry("run.id", runId.toString())
                    .containsEntry("last.event.id", "1")
                    .containsEntry("event.count", "2")
                    .containsEntry("reset", "false");
        });
    }

    @Test
    void runEventsDeliversTerminalPublishedAfterStreamOpens() throws Exception {
        var created = createGuestExploration(ownerToken, "kr-45-jeonju");
        var tree = objectMapper.readTree(created);
        var explorationId = UUID.fromString(tree.path("explorationId").asText());
        var runId = UUID.fromString(tree.path("runId").asText());
        var eventsUrl = tree.path("eventsUrl").asText();

        var result = mockMvc.perform(get(eventsUrl).cookie(guestCookie(ownerToken)))
                .andExpect(request().asyncStarted())
                .andReturn();

        explorationService.completeRun(explorationId, runId, ExplorationRunOutcome.INITIAL_BOARD);

        var body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("event:run.stage");
        assertThat(body).contains("\"status\":\"QUEUED\"");
        assertThat(body).contains("event:run.terminal");
        assertThat(body).contains("\"outcome\":\"INITIAL_BOARD\"");
    }

    @Test
    void runEventsReturnsResetAndReportsTelemetryWhenLastEventIdMissesBuffer() throws Exception {
        var created = createGuestExploration(ownerToken, "kr-45-jeonju");
        var tree = objectMapper.readTree(created);
        var runId = UUID.fromString(tree.path("runId").asText());
        var eventsUrl = tree.path("eventsUrl").asText();

        var body = mockMvc.perform(get(eventsUrl)
                        .cookie(guestCookie(ownerToken))
                        .header("Last-Event-ID", "0"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("id:");
        assertThat(body).contains("event:reset");
        assertThat(body).contains("\"runId\":\"" + runId + "\"");
        assertThat(telemetrySink.events()).anySatisfy(event -> {
            assertThat(event.name()).isEqualTo("journey.sse.reset");
            assertThat(event.attributes())
                    .containsEntry("run.id", runId.toString())
                    .containsEntry("last.event.id", "0");
        });
    }

    @Test
    void runEventsWithoutValidActorClosesWithZeroDataAuthFrame() throws Exception {
        var body = mockMvc.perform(get("/api/v1/explorations/{explorationId}/runs/{runId}/events",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/event-stream;charset=UTF-8"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isEqualTo("""
                event:auth_closed
                data:0

                """);
        assertThat(telemetrySink.events()).anySatisfy(event -> {
            assertThat(event.name()).isEqualTo("journey.sse.auth_closed");
            assertThat(event.attributes()).containsEntry("reason", "AUTH_REQUIRED");
        });
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

    private byte[] createTurnWithKey(String explorationId, byte[] body, UUID key) throws Exception {
        return mockMvc.perform(post("/api/v1/explorations/{id}/turns", explorationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .cookie(guestCookie(ownerToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", key))
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

    private void cancelRun(String guestToken, String explorationId, String runId) throws Exception {
        mockMvc.perform(post("/api/v1/explorations/{explorationId}/runs/{runId}/cancel", explorationId, runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(guestCookie(guestToken), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk());
    }

    private double counter(String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private Cookie guestCookie(String token) {
        return new Cookie("__Host-onmaru-guest", token);
    }

    private void seedPublicJeonjuPlace() {
        placeDetailStore.add(PlaceProjection.publicPlace(
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                "한옥",
                new RegionProjection("kr-45-jeonju", "전북 전주시"),
                "전북 전주시 완산구 기린대로 99",
                new CoordinatesProjection(35.8151, 127.153),
                List.of(new ImageProjection(
                        "https://cdn.onmaru.example/places/p-jeonju-hanok-village/cover.jpg",
                        "전주 한옥마을 골목")),
                "전통 한옥과 공예, 음식, 산책 코스를 한 번에 경험할 수 있는 공개 관광 장소입니다.",
                List.of("한옥 골목", "공예 체험", "야간 산책"),
                "odii-jeonju-hanok-village"));
    }

    @TestConfiguration
    static class TelemetryTestConfig {

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

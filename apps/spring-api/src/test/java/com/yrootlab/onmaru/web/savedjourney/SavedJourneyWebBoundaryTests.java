package com.yrootlab.onmaru.web.savedjourney;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.journey.actions.ResourceRef;
import com.yrootlab.onmaru.journey.exploration.CreateExplorationCommand;
import com.yrootlab.onmaru.journey.exploration.ExplorationActor;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
import com.yrootlab.onmaru.journey.exploration.ExplorationService;
import com.yrootlab.onmaru.journey.savedjourney.InMemorySavedJourneyStore;
import com.yrootlab.onmaru.journey.savedjourney.SavedJourneySnapshot;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class SavedJourneyWebBoundaryTests {

    private static final Cookie CSRF_COOKIE = new Cookie("__Host-onmaru-csrf", "csrf-token");

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private ExplorationService explorationService;

    @Autowired
    private InMemorySavedJourneyStore savedJourneyStore;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    private UUID memberId;
    private UUID otherMemberId;

    @BeforeEach
    void setUp() {
        identityStore.clear();
        savedJourneyStore.clear();
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
    }

    @Test
    void createListDetailAndDeleteAreOwnerScoped() throws Exception {
        var explorationId = completedExploration(memberId);

        var created = mockMvc.perform(post("/api/v1/saved-journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "explorationId", explorationId.toString(),
                                "baseVersion", 0,
                                "title", "전주 한옥 산책")))
                        .cookie(session("member-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.savedJourneyId", not(emptyOrNullString())))
                .andExpect(jsonPath("$.title").value("전주 한옥 산책"))
                .andExpect(jsonPath("$.snapshot.board.candidates[0].placeRef.id").value("p-jeonju-hanok-village"))
                .andReturn().getResponse().getContentAsByteArray();

        var savedJourneyId = objectMapper.readTree(created).path("savedJourneyId").asText();

        mockMvc.perform(get("/api/v1/saved-journeys").cookie(session("member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].savedJourneyId").value(savedJourneyId))
                .andExpect(jsonPath("$.hasMore").value(false));

        mockMvc.perform(get("/api/v1/saved-journeys/{id}", savedJourneyId).cookie(session("other-session")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/saved-journeys/{id}", savedJourneyId)
                        .cookie(session("member-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void resumeReturnsNewExplorationAndUnavailableRefs() throws Exception {
        var saved = savedJourneyStore.save(memberId, SavedJourneySnapshot.seed(
                UUID.randomUUID(),
                7,
                "숨겨진 장소",
                "kr-45-jeonju",
                List.of(new ResourceRef("PLACE", "hidden")),
                List.of(new ResourceRef("PLACE", "hidden")),
                List.of(),
                clock.instant()), clock.instant(), 100).journey();

        mockMvc.perform(post("/api/v1/saved-journeys/{id}/resume", saved.savedJourneyId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(session("member-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.unavailableRefs[0].id").value("hidden"))
                .andExpect(jsonPath("$.exploration.stateVersion").value(0))
                .andExpect(jsonPath("$.exploration.board").doesNotExist());
    }

    private UUID completedExploration(UUID owner) {
        var actor = ExplorationActor.member(owner);
        var created = explorationService.create(actor, new CreateExplorationCommand("전주 한옥", "ko-KR", "kr-45-jeonju"));
        explorationService.claimRun(created.explorationId(), created.run().id(), "INTERPRETING");
        explorationService.completeRun(created.explorationId(), created.run().id(), ExplorationRunOutcome.INITIAL_BOARD);
        return created.explorationId();
    }

    private Cookie session(String value) {
        return new Cookie("__Host-onmaru-session", value);
    }
}

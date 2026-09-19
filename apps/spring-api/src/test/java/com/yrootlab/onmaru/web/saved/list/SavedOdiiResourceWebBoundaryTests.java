package com.yrootlab.onmaru.web.saved.list;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.audio.query.InMemoryOdiiStoryQueryStore;
import com.yrootlab.onmaru.audio.query.OdiiActiveSnapshot;
import com.yrootlab.onmaru.audio.query.OdiiCoordinates;
import com.yrootlab.onmaru.audio.query.OdiiRegionRef;
import com.yrootlab.onmaru.audio.query.OdiiStoryProjection;
import com.yrootlab.onmaru.audio.query.OdiiTranscriptLine;
import com.yrootlab.onmaru.audio.query.OdiiTranscriptStatus;
import com.yrootlab.onmaru.audio.sync.AudioStatus;
import com.yrootlab.onmaru.catalog.application.query.detail.CoordinatesProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.ImageProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.RegionProjection;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.journey.saved.list.SavedResourceRecord;
import com.yrootlab.onmaru.journey.saved.odii.InMemorySavedOdiiStoryStore;
import com.yrootlab.onmaru.journey.saved.place.InMemorySavedPlaceStore;
import com.yrootlab.onmaru.journey.saved.place.SavedResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {OnMaruApplication.class, SavedOdiiResourceWebBoundaryTests.TestPorts.class},
        properties = {
                "onmaru.secrets.source=fake",
                "onmaru.audio.public-hosts=cdn.onmaru.example"
        })
@AutoConfigureMockMvc
class SavedOdiiResourceWebBoundaryTests {

    private static final UUID REVISION = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String STORY_ONE = "odii-story-jeonju-01";
    private static final String STORY_TWO = "odii-story-jeonju-02";
    private static final String HIDDEN_STORY = "odii-story-hidden-01";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private InMemorySavedPlaceStore savedPlaceStore;

    @Autowired
    private InMemorySavedOdiiStoryStore savedOdiiStoryStore;

    @Autowired
    private InMemoryPlaceDetailStore placeDetailStore;

    @Autowired
    private InMemoryOdiiStoryQueryStore storyStore;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    private UUID memberId;

    @BeforeEach
    void setUp() {
        identityStore.clear();
        savedPlaceStore.clear();
        savedOdiiStoryStore.clear();
        placeDetailStore.clear();
        memberId = member("member-session");
        member("other-session");
        storyStore.replaceActive(new OdiiActiveSnapshot(REVISION, List.of(
                story(STORY_ONE, "odii-spot-jeonju-01", AudioStatus.ACTIVE, Instant.parse("2026-09-15T03:00:00Z")),
                story(STORY_TWO, "odii-spot-jeonju-02", AudioStatus.ACTIVE, Instant.parse("2026-09-15T02:00:00Z")),
                story(HIDDEN_STORY, "odii-spot-hidden-01", AudioStatus.HIDDEN, Instant.parse("2026-09-15T01:00:00Z")))));
    }

    @Test
    void repeatedPutSavesOneOdiiStoryWithoutSavingItsLinkedPlace() throws Exception {
        var first = save(STORY_ONE).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.resourceType").value("ODII_STORY"))
                .andExpect(jsonPath("$.resourceId").value(STORY_ONE))
                .andExpect(jsonPath("$.storyId").value(STORY_ONE))
                .andExpect(jsonPath("$.savedByMe").value(true))
                .andExpect(jsonPath("$.savedAt", not(emptyOrNullString())))
                .andReturn().getResponse().getContentAsString();
        var firstRow = savedOdiiStoryStore.records(memberId, SavedResourceType.ODII_STORY).getFirst();

        var second = save(STORY_ONE).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var secondRow = savedOdiiStoryStore.records(memberId, SavedResourceType.ODII_STORY).getFirst();

        assertThat(OBJECT_MAPPER.readTree(second).get("savedAt"))
                .isEqualTo(OBJECT_MAPPER.readTree(first).get("savedAt"));
        assertThat(secondRow.id()).isEqualTo(firstRow.id());
        assertThat(secondRow.savedAt()).isEqualTo(firstRow.savedAt());
        assertThat(savedOdiiStoryStore.records(memberId, SavedResourceType.ODII_STORY)).hasSize(1);
        assertThat(savedPlaceStore.countFor(memberId, SavedResourceType.PLACE)).isZero();
    }

    @Test
    void deleteIsIdempotentAndCommandsEnforceAuthenticationAndCsrf() throws Exception {
        save(STORY_ONE).andExpect(status().isOk());

        deleteSaved(STORY_ONE).andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        deleteSaved(STORY_ONE).andExpect(status().isNoContent());

        mockMvc.perform(put("/api/v1/saved-resources/odii-stories/{storyId}", STORY_ONE)
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/saved-resources/odii-stories/{storyId}", STORY_ONE)
                .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void rejectsHiddenStoriesAndFiltersStoriesThatBecomeHiddenAfterSave() throws Exception {
        save(HIDDEN_STORY).andExpect(status().isNotFound());
        save(STORY_ONE).andExpect(status().isOk());
        storyStore.replaceActive(new OdiiActiveSnapshot(REVISION, List.of(
                story(STORY_ONE, "odii-spot-jeonju-01", AudioStatus.DELETED,
                        Instant.parse("2026-09-15T03:00:00Z")))));

        list("member-session", "ODII_STORY", 20, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void enforcesTheOdiiTypeLimitWithoutBreakingDuplicatePutIdempotency() throws Exception {
        for (int index = 0; index < 299; index++) {
            savedOdiiStoryStore.save(
                    memberId,
                    "odii-story-limit-%03d".formatted(index),
                    clock.instant(),
                    300);
        }

        save(STORY_ONE).andExpect(status().isOk());
        save(STORY_ONE).andExpect(status().isOk());
        save(STORY_TWO).andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("SAVE_LIMIT"))
                .andExpect(jsonPath("$.details.limit").value(300));
    }

    @Test
    void listsOnlyTheRequiredTypeWithCurrentHydrationAndActorBoundStableCursor() throws Exception {
        save(STORY_ONE).andExpect(status().isOk());
        save(STORY_TWO).andExpect(status().isOk());
        var expectedStoryIds = savedOdiiStoryStore.records(memberId, SavedResourceType.ODII_STORY).stream()
                .sorted(Comparator.comparing(SavedResourceRecord::id).reversed())
                .map(SavedResourceRecord::resourceId)
                .toList();

        var response = list("member-session", "ODII_STORY", 1, null)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items[0].resourceType").value("ODII_STORY"))
                .andExpect(jsonPath("$.items[0].storyId").value(expectedStoryIds.getFirst()))
                .andExpect(jsonPath("$.items[0].savedByMe").value(true))
                .andExpect(jsonPath("$.items[0].id").doesNotExist())
                .andExpect(jsonPath("$.items[0].stid").doesNotExist())
                .andExpect(jsonPath("$.items[0].stlid").doesNotExist())
                .andExpect(jsonPath("$.nextCursor", not(emptyOrNullString())))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn().getResponse().getContentAsString();
        var cursor = OBJECT_MAPPER.readTree(response).get("nextCursor").asText();

        list("member-session", "ODII_STORY", 1, cursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].storyId").value(expectedStoryIds.get(1)));
        list("other-session", "ODII_STORY", 1, cursor)
                .andExpect(status().isNotFound());
        list("member-session", "PLACE", 1, cursor)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
        list("member-session", "ODII_STORY", 1, cursor + "x")
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
        list("member-session", "ODII_STORY", 1, "x".repeat(513))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
        list("member-session", "PLACE", 20, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get("/api/v1/saved-resources")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field").value("type"));
    }

    @Test
    void paginatesPlacesByOpaqueRowIdWithoutPublishingIt() throws Exception {
        addPublicPlace("p-place-one", "첫 장소");
        addPublicPlace("p-place-two", "둘째 장소");
        savedPlaceStore.save(memberId, "p-place-one", clock.instant(), 500);
        savedPlaceStore.save(memberId, "p-place-two", clock.instant(), 500);
        var expectedPlaceIds = savedPlaceStore.records(memberId, SavedResourceType.PLACE).stream()
                .sorted(Comparator.comparing(SavedResourceRecord::id).reversed())
                .map(SavedResourceRecord::resourceId)
                .toList();

        var response = list("member-session", "PLACE", 1, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].placeId").value(expectedPlaceIds.getFirst()))
                .andExpect(jsonPath("$.items[0].id").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        var cursor = OBJECT_MAPPER.readTree(response).get("nextCursor").asText();

        list("member-session", "PLACE", 1, cursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].placeId").value(expectedPlaceIds.get(1)));
    }

    @Test
    void mapsUnavailableCurrentSourcesToPrivateServiceUnavailableErrors() throws Exception {
        storyStore.markUnavailable();
        save(STORY_ONE)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

        storyStore.replaceActive(new OdiiActiveSnapshot(REVISION, List.of(
                story(STORY_ONE, "odii-spot-jeonju-01", AudioStatus.ACTIVE,
                        Instant.parse("2026-09-15T03:00:00Z")))));
        save(STORY_ONE).andExpect(status().isOk());
        storyStore.markUnavailable();
        list("member-session", "ODII_STORY", 20, null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

        savedPlaceStore.save(memberId, "p-place-unavailable", clock.instant(), 500);
        placeDetailStore.markUnavailable();
        savePlace("p-place-unavailable")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        list("member-session", "PLACE", 20, null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    private void addPublicPlace(String placeId, String name) {
        placeDetailStore.add(PlaceProjection.publicPlace(
                placeId,
                name,
                "한옥",
                new RegionProjection("kr-45-jeonju", "전북 전주시"),
                "전북 전주시",
                new CoordinatesProjection(35.8, 127.1),
                List.of(new ImageProjection("https://cdn.onmaru.example/places/" + placeId + ".jpg", name)),
                "설명",
                List.of(),
                null));
    }

    private UUID member(String sessionToken) {
        var id = identityStore.createMember(clock.instant());
        identityStore.saveSession(new SessionRecord(
                hasher.hash(sessionToken), id, clock.instant(), clock.instant(), clock.instant().plusSeconds(3600)));
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions save(String storyId) throws Exception {
        return mockMvc.perform(put("/api/v1/saved-resources/odii-stories/{storyId}", storyId)
                .cookie(
                        new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session"),
                        new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                .header("X-CSRF-TOKEN", "csrf-token"));
    }

    private org.springframework.test.web.servlet.ResultActions deleteSaved(String storyId) throws Exception {
        return mockMvc.perform(delete("/api/v1/saved-resources/odii-stories/{storyId}", storyId)
                .cookie(
                        new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session"),
                        new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                .header("X-CSRF-TOKEN", "csrf-token"));
    }

    private org.springframework.test.web.servlet.ResultActions savePlace(String placeId) throws Exception {
        return mockMvc.perform(put("/api/v1/saved-resources/places/{placeId}", placeId)
                .cookie(
                        new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session"),
                        new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                .header("X-CSRF-TOKEN", "csrf-token"));
    }

    private org.springframework.test.web.servlet.ResultActions list(
            String session,
            String type,
            int limit,
            String cursor) throws Exception {
        var request = get("/api/v1/saved-resources")
                .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", session))
                .queryParam("type", type)
                .queryParam("limit", String.valueOf(limit));
        if (cursor != null) {
            request.queryParam("cursor", cursor);
        }
        return mockMvc.perform(request);
    }

    private OdiiStoryProjection story(String storyId, String spotId, AudioStatus status, Instant publishedAt) {
        return new OdiiStoryProjection(
                storyId,
                spotId,
                "ko-KR",
                "이야기 " + storyId,
                "오디오 " + storyId,
                "한옥/고택",
                new OdiiRegionRef("kr-45-jeonju", "전북 전주시", "CITY", "kr-45"),
                new OdiiCoordinates(35.817632, 127.152948),
                480,
                "https://cdn.onmaru.example/odii/" + storyId + ".jpg",
                "https://cdn.onmaru.example/odii/" + storyId + ".mp3",
                OdiiTranscriptStatus.OFFICIAL,
                List.of(new OdiiTranscriptLine(0, 0, "공개 대본")),
                List.of(),
                publishedAt,
                status,
                status);
    }

    @TestConfiguration
    static class TestPorts {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-16T04:00:00Z"), java.time.ZoneOffset.UTC);
        }

        @Bean
        @Primary
        InMemoryOdiiStoryQueryStore testOdiiStoryQueryStore() {
            return new InMemoryOdiiStoryQueryStore();
        }
    }
}

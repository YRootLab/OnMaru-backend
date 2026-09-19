package com.yrootlab.onmaru.web.saved.place;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.RegionProjection;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.journey.saved.place.InMemorySavedPlaceStore;
import com.yrootlab.onmaru.journey.saved.place.SavedResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class SavedPlaceWebBoundaryTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private InMemoryPlaceDetailStore placeStore;

    @Autowired
    private InMemorySavedPlaceStore savedPlaceStore;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    private UUID memberId;

    @BeforeEach
    void setUp() {
        identityStore.clear();
        placeStore.clear();
        savedPlaceStore.clear();
        memberId = identityStore.createMember(clock.instant());
        identityStore.saveSession(new SessionRecord(
                hasher.hash("member-session"),
                memberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600)));
        placeStore.add(PlaceProjection.publicPlace(
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                "한옥",
                new RegionProjection("kr-45-jeonju", "전북 전주시"),
                "전북 전주시 완산구 기린대로 99",
                null,
                List.of(),
                "공개 가능한 canonical 장소입니다.",
                List.of(),
                null));
        placeStore.add(PlaceProjection.hidden("p-private-place"));
    }

    @Test
    void repeatedPutCreatesOneSavedPlaceAndUpdatesDetailSavedState() throws Exception {
        savePlace()
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.resourceType").value("PLACE"))
                .andExpect(jsonPath("$.resourceId").value("p-jeonju-hanok-village"))
                .andExpect(jsonPath("$.placeId").value("p-jeonju-hanok-village"))
                .andExpect(jsonPath("$.savedByMe").value(true))
                .andExpect(jsonPath("$.savedAt", not(emptyOrNullString())));

        savePlace().andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(savedPlaceStore.countFor(memberId, SavedResourceType.PLACE))
                .isEqualTo(1);
        mockMvc.perform(get("/api/v1/places/p-jeonju-hanok-village")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedByMe").value(true));
    }

    @Test
    void repeatedDeleteSucceedsWithNoContent() throws Exception {
        savePlace().andExpect(status().isOk());

        deletePlace().andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        deletePlace().andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(savedPlaceStore.savedBy(memberId, "p-jeonju-hanok-village"))
                .isFalse();
    }

    @Test
    void saveRequiresAuthenticatedMember() throws Exception {
        mockMvc.perform(put("/api/v1/saved-resources/places/p-jeonju-hanok-village")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("X-Request-Id", "req-save-place-auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Authentication is required to save this place."))
                .andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.not("req-save-place-auth")));
    }

    @Test
    void saveRejectsPrivateOrUnknownPlaceAsNotFound() throws Exception {
        mockMvc.perform(put("/api/v1/saved-resources/places/p-private-place")
                        .cookie(
                                new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session"),
                                new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("X-Request-Id", "req-save-place-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("The place is not available."))
                .andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.not("req-save-place-not-found")));
    }

    @Test
    void saveLimitReturnsConflictEnvelope() throws Exception {
        for (var index = 0; index < 500; index++) {
            savedPlaceStore.save(memberId, "p-already-saved-" + index, clock.instant(), 500);
        }

        mockMvc.perform(put("/api/v1/saved-resources/places/p-jeonju-hanok-village")
                        .cookie(
                                new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session"),
                                new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("X-Request-Id", "req-save-place-limit"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAVE_LIMIT"))
                .andExpect(jsonPath("$.message").value("Saved resource limit exceeded."))
                .andExpect(jsonPath("$.details.limit").value(500));
    }

    private org.springframework.test.web.servlet.ResultActions savePlace() throws Exception {
        return mockMvc.perform(put("/api/v1/saved-resources/places/p-jeonju-hanok-village")
                .cookie(
                        new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session"),
                        new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                .header("X-CSRF-TOKEN", "csrf-token"));
    }

    private org.springframework.test.web.servlet.ResultActions deletePlace() throws Exception {
        return mockMvc.perform(delete("/api/v1/saved-resources/places/p-jeonju-hanok-village")
                .cookie(
                        new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session"),
                        new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                .header("X-CSRF-TOKEN", "csrf-token"));
    }
}

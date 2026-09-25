package com.yrootlab.onmaru.web.place.detail;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.catalog.application.query.detail.CoordinatesProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.ImageProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.RegionProjection;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.journey.saved.place.InMemorySavedPlaceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class PlaceDetailWebBoundaryTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryPlaceDetailStore placeStore;

    @Autowired
    private InMemorySavedPlaceStore savedPlaceStore;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    private UUID memberId;

    @BeforeEach
    void setUp() {
        placeStore.clear();
        savedPlaceStore.clear();
        identityStore.clear();
        memberId = identityStore.createMember(clock.instant());
        identityStore.saveSession(new SessionRecord(
                hasher.hash("member-session"),
                memberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600)));
        savedPlaceStore.save(memberId, "p-jeonju-hanok-village", clock.instant(), 500);
        placeStore.add(PlaceProjection.publicPlace(
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
        placeStore.add(PlaceProjection.hidden("p-private-or-unmapped"));
    }

    @Test
    void canonicalPlaceDetailMatchesPublicContractAndSavedState() throws Exception {
        mockMvc.perform(get("/api/v1/places/p-jeonju-hanok-village")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.placeId").value("p-jeonju-hanok-village"))
                .andExpect(jsonPath("$.name").value("전주 한옥마을"))
                .andExpect(jsonPath("$.category").value("한옥"))
                .andExpect(jsonPath("$.region.regionCode").value("kr-45-jeonju"))
                .andExpect(jsonPath("$.coordinates.lat").value(35.8151))
                .andExpect(jsonPath("$.images[0].url").value("https://cdn.onmaru.example/places/p-jeonju-hanok-village/cover.jpg"))
                .andExpect(jsonPath("$.contentTags", hasItem("한옥 골목")))
                .andExpect(jsonPath("$.savedByMe").value(true));
    }

    @Test
    void legacySingularPlacePathKeepsTheCanonicalPlaceContract() throws Exception {
        mockMvc.perform(get("/api/place/p-jeonju-hanok-village"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.placeId").value("p-jeonju-hanok-village"));
    }

    @Test
    void hanokDetailUsesSameCanonicalPlaceIdForLinkedCards() throws Exception {
        mockMvc.perform(get("/api/v1/hanoks/p-jeonju-hanok-village")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.placeId").value("p-jeonju-hanok-village"))
                .andExpect(jsonPath("$.category").value("HANOK"))
                .andExpect(jsonPath("$.savedByMe").value(true))
                .andExpect(jsonPath("$.contentTags", hasItem("한옥 골목")))
                .andExpect(jsonPath("$.mapCard.placeId").value("p-jeonju-hanok-village"))
                .andExpect(jsonPath("$.mapCard.savedByMe").value(true))
                .andExpect(jsonPath("$.odiiLinkedCard.placeId").value("p-jeonju-hanok-village"));
    }

    @Test
    void unauthenticatedPlaceDetailKeepsSavedStateFalseAndNullProviderFields() throws Exception {
        placeStore.add(PlaceProjection.publicPlace(
                "p-null-provider-fields",
                "미상 한옥",
                "한옥",
                new RegionProjection("kr-11-seoul", "서울 종로구"),
                null,
                null,
                List.of(),
                "공개 가능한 canonical 장소입니다.",
                List.of(),
                null));

        mockMvc.perform(get("/api/v1/places/p-null-provider-fields"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address", nullValue()))
                .andExpect(jsonPath("$.coordinates", nullValue()))
                .andExpect(jsonPath("$.images").isArray())
                .andExpect(jsonPath("$.savedByMe").value(false));
    }

    @Test
    void privateOrUnmappedPlaceReturnsPublicNotFoundEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/places/p-private-or-unmapped")
                        .header("X-Request-Id", "req-r1-place-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("The requested place is not available."))
                .andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.not("req-r1-place-not-found")))
                .andExpect(jsonPath("$.details.resourceType").value("PLACE"));
    }

    @Test
    void sourceOutageReturnsServiceUnavailableEnvelope() throws Exception {
        placeStore.markUnavailable();

        mockMvc.perform(get("/api/v1/hanoks/p-jeonju-hanok-village")
                        .header("X-Request-Id", "req-r1-service-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Catalog data is temporarily unavailable."))
                .andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.not("req-r1-service-unavailable")))
                .andExpect(jsonPath("$.details.retryAfterMs").value(30000));
    }
}

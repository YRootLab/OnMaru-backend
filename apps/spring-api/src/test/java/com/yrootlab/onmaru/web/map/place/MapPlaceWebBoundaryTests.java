package com.yrootlab.onmaru.web.map.place;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.catalog.application.query.spatial.InMemoryMapPlaceStore;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapCoordinates;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapCoverageStatus;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapDataAvailability;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceStatus;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapRegionRef;
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

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class MapPlaceWebBoundaryTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryMapPlaceStore mapPlaceStore;

    @Autowired
    private InMemorySavedPlaceStore savedPlaceStore;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");

    @BeforeEach
    void setUp() {
        mapPlaceStore.clear();
        savedPlaceStore.clear();
        identityStore.clear();
        var memberId = identityStore.createMember(clock.instant());
        identityStore.saveSession(new SessionRecord(
                hasher.hash("member-session"),
                memberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600)));
        savedPlaceStore.save(memberId, "p-jeonju-hanok-village", clock.instant(), 500);
        seedPlaces();
    }

    @Test
    void listMapPlacesMatchesR2FixtureWithSavedState() throws Exception {
        mockMvc.perform(get("/api/v1/map/places")
                        .param("regionCode", "kr-45-jeonju")
                        .param("language", "ko-KR")
                        .param("limit", "2")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.coverageStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.language").value("ko-KR"))
                .andExpect(jsonPath("$.items[0].placeId").value("p-jeonju-hanok-village"))
                .andExpect(jsonPath("$.items[0].name").value("전주 한옥마을"))
                .andExpect(jsonPath("$.items[0].category").value("한옥"))
                .andExpect(jsonPath("$.items[0].region.regionCode").value("kr-45-jeonju"))
                .andExpect(jsonPath("$.items[0].region.name").value("전북 전주시"))
                .andExpect(jsonPath("$.items[0].region.level").value("CITY"))
                .andExpect(jsonPath("$.items[0].region.parentRegionCode").value("kr-45"))
                .andExpect(jsonPath("$.items[0].coordinates.lat").value(35.8151))
                .andExpect(jsonPath("$.items[0].coordinates.lng").value(127.1530))
                .andExpect(jsonPath("$.items[0].savedByMe").value(true))
                .andExpect(jsonPath("$.items[0].linkedOdiiStoryIds[0]").value("odii-story-jeonju-hanok-01"))
                .andExpect(jsonPath("$.items[0].dataAvailability.place").value("COMPLETE"))
                .andExpect(jsonPath("$.items[0].dataAvailability.observation").value("PARTIAL"))
                .andExpect(jsonPath("$.items[0].dataAvailability.odii").value("COMPLETE"))
                .andExpect(jsonPath("$.nextCursor", nullValue()))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void bboxAndCategoryFilterUseLongitudeLatitudeAxis() throws Exception {
        mockMvc.perform(get("/api/v1/map/places")
                        .param("bbox", "127.152,35.814,127.154,35.816")
                        .param("category", "한옥")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].placeId").value("p-jeonju-hanok-village"))
                .andExpect(jsonPath("$.items[1]").doesNotExist());
    }

    @Test
    void supportedFilterWithNoResultsReturnsMissingPage() throws Exception {
        mockMvc.perform(get("/api/v1/map/places")
                        .param("regionCode", "kr-45-muju")
                        .param("language", "ko-KR")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverageStatus").value("MISSING"))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor", nullValue()))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void invalidBboxReturnsSharedPublicErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/map/places")
                        .param("bbox", "invalid")
                        .header("X-Request-Id", "req-r2-invalid-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("The requested map bounds are invalid."))
                .andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.not("req-r2-invalid-request")))
                .andExpect(jsonPath("$.details.field").value("bbox"));
    }

    @Test
    void snapshotOutageReturnsServiceUnavailableEnvelope() throws Exception {
        mapPlaceStore.markUnavailable();

        mockMvc.perform(get("/api/v1/map/places")
                        .param("regionCode", "kr-45-jeonju")
                        .header("X-Request-Id", "req-r2-service-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Catalog data is temporarily unavailable."))
                .andExpect(jsonPath("$.details.retryAfterMs").value(30000));
    }

    private void seedPlaces() {
        mapPlaceStore.add(place(
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                "한옥",
                "kr-45-jeonju",
                "전북 전주시",
                35.8151,
                127.1530,
                "한옥 골목과 전통 체험을 함께 둘러볼 수 있는 대표 한옥 권역입니다.",
                List.of("odii-story-jeonju-hanok-01")));
        mapPlaceStore.add(place(
                "p-jeonju-gyodong-tea",
                "교동 찻집",
                "카페",
                "kr-45-jeonju",
                "전북 전주시",
                35.8159,
                127.1540,
                "한옥 골목 사이의 조용한 찻집입니다.",
                List.of()));
        mapPlaceStore.add(place(
                "p-hidden",
                "숨김 장소",
                "한옥",
                "kr-45-jeonju",
                "전북 전주시",
                35.8152,
                127.1532,
                "숨김 처리된 장소입니다.",
                List.of()).hidden());
    }

    private MapPlaceProjection place(
            String placeId,
            String name,
            String category,
            String regionCode,
            String regionName,
            double lat,
            double lng,
            String summary,
            List<String> linkedOdiiStoryIds) {
        return new MapPlaceProjection(
                placeId,
                name,
                category,
                new MapRegionRef(regionCode, regionName, "CITY", "kr-45"),
                new MapCoordinates(lat, lng),
                "https://cdn.onmaru.example/places/" + placeId + "/cover.jpg",
                summary,
                linkedOdiiStoryIds,
                new MapDataAvailability(MapCoverageStatus.COMPLETE, MapCoverageStatus.PARTIAL, MapCoverageStatus.COMPLETE),
                MapPlaceStatus.PUBLIC);
    }
}

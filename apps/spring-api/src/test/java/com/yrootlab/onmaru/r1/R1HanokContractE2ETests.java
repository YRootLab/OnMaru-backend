package com.yrootlab.onmaru.r1;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.catalog.application.query.detail.CoordinatesProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.ImageProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.RegionProjection;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListCategory;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListProjection;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStatus;
import com.yrootlab.onmaru.catalog.application.query.hanok.InMemoryHanokListStore;
import com.yrootlab.onmaru.catalog.editorial.InMemoryMonthlyHanokEditionStore;
import com.yrootlab.onmaru.catalog.editorial.MonthlyHanokEditionDraft;
import com.yrootlab.onmaru.catalog.editorial.MonthlyHanokPlacementDraft;
import com.yrootlab.onmaru.catalog.editorial.MonthlyHanokSlot;
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
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class R1HanokContractE2ETests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private InMemorySavedPlaceStore savedPlaceStore;

    @Autowired
    private InMemoryPlaceDetailStore placeDetailStore;

    @Autowired
    private InMemoryHanokListStore hanokListStore;

    @Autowired
    private InMemoryMonthlyHanokEditionStore monthlyStore;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");

    @BeforeEach
    void setUp() {
        identityStore.clear();
        savedPlaceStore.clear();
        placeDetailStore.clear();
        hanokListStore.clear();
        monthlyStore.clear();
        seedPlaces();
        seedMonthly();
        var memberId = identityStore.createMember(clock.instant());
        identityStore.saveSession(new SessionRecord(
                hasher.hash("member-session"),
                memberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600)));
    }

    @Test
    void savedPlaceStateFlowsAcrossListDetailMonthlyAndDeleteIsIdempotent() throws Exception {
        mockMvc.perform(get("/api/v1/hanoks").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].savedByMe").value(false));

        savePlace().andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("PLACE"))
                .andExpect(jsonPath("$.placeId").value("p-jeonju-hanok-village"))
                .andExpect(jsonPath("$.savedByMe").value(true));
        savePlace().andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/hanoks")
                        .param("limit", "1")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].placeId").value("p-jeonju-hanok-village"))
                .andExpect(jsonPath("$.items[0].savedByMe").value(true));
        mockMvc.perform(get("/api/v1/places/p-jeonju-hanok-village")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedByMe").value(true));
        mockMvc.perform(get("/api/v1/hanoks/monthly")
                        .param("month", "2026-09")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placements[0].place.savedByMe").value(true));

        deletePlace().andExpect(status().isNoContent());
        deletePlace().andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/places/p-jeonju-hanok-village")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedByMe").value(false));
    }

    @Test
    void r1ReadFailuresKeepStablePublicEnvelope() throws Exception {
        hanokListStore.markUnavailable();
        mockMvc.perform(get("/api/v1/hanoks")
                        .header("X-Request-Id", "req-list-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.retryAfterMs").value(30000));
        hanokListStore.clear();
        seedHanokCards();

        placeDetailStore.markUnavailable();
        mockMvc.perform(get("/api/v1/places/p-jeonju-hanok-village")
                        .header("X-Request-Id", "req-detail-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.retryAfterMs").value(30000));
        placeDetailStore.clear();
        seedPlaceDetails();

        monthlyStore.markUnavailable();
        mockMvc.perform(get("/api/v1/hanoks/monthly")
                        .param("month", "2026-09")
                        .header("X-Request-Id", "req-monthly-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.retryAfterMs").value(30000));
    }

    @Test
    void runtimeResponsesCarryR1FixtureShape() throws Exception {
        mockMvc.perform(get("/api/v1/hanoks")
                        .param("limit", "2")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.items[1].thumbnailUrl", nullValue()))
                .andExpect(jsonPath("$.nextCursor").value("r1.hanoks.cursor.2026-09-14T08:00:00Z.p-bukchon-hanok-cafe"));
        mockMvc.perform(get("/api/v1/hanoks/monthly").param("month", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.placements").isEmpty());
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

    private void seedPlaces() {
        seedPlaceDetails();
        seedHanokCards();
    }

    private void seedPlaceDetails() {
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

    private void seedHanokCards() {
        hanokListStore.add(card(
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                HanokListCategory.HANOK,
                "전북 전주시",
                "https://cdn.onmaru.example/places/p-jeonju-hanok-village/cover.jpg",
                "한옥 골목과 전통 체험을 함께 둘러볼 수 있는 대표 한옥 권역입니다.",
                List.of("한옥", "체험", "산책"),
                Instant.parse("2026-09-14T09:00:00Z")));
        hanokListStore.add(card(
                "p-bukchon-hanok-cafe",
                "북촌 한옥 찻집",
                HanokListCategory.HANOK_CAFE,
                "서울 종로구",
                null,
                "한옥 구조를 보존한 조용한 찻집입니다.",
                List.of("카페", "북촌"),
                Instant.parse("2026-09-14T08:00:00Z")));
        hanokListStore.add(card(
                "p-gyeongju-gyochon",
                "경주 교촌 한옥마을",
                HanokListCategory.HANOK,
                "경북 경주시",
                "https://cdn.onmaru.example/places/p-gyeongju-gyochon/cover.jpg",
                "월정교와 함께 둘러보기 좋은 전통 한옥 권역입니다.",
                List.of("한옥", "경주"),
                Instant.parse("2026-09-14T07:00:00Z")));
    }

    private HanokListProjection card(
            String placeId,
            String name,
            HanokListCategory category,
            String regionName,
            String thumbnailUrl,
            String summary,
            List<String> tags,
            Instant publishedAt) {
        return new HanokListProjection(
                placeId,
                name,
                category,
                "kr-test",
                regionName,
                thumbnailUrl,
                summary,
                tags,
                publishedAt,
                HanokListStatus.PUBLIC);
    }

    private void seedMonthly() {
        monthlyStore.publish(new MonthlyHanokEditionDraft(
                YearMonth.of(2026, 9),
                "9월의 한옥 산책",
                "선선한 저녁에 걷기 좋은 한옥 장소를 모았습니다.",
                List.of(
                        new MonthlyHanokPlacementDraft(
                                MonthlyHanokSlot.HERO,
                                "대표 한옥 권역으로 첫 화면에서 소개합니다.",
                                "p-jeonju-hanok-village"),
                        new MonthlyHanokPlacementDraft(
                                MonthlyHanokSlot.CAFE,
                                "한옥 카페를 찾는 사용자를 위한 보조 placement입니다.",
                                "p-bukchon-hanok-cafe"))));
    }
}

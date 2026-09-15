package com.yrootlab.onmaru.web.hanok.list;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListCategory;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListProjection;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStatus;
import com.yrootlab.onmaru.catalog.application.query.hanok.InMemoryHanokListStore;
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
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class HanokListWebBoundaryTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryHanokListStore hanokListStore;

    @Autowired
    private InMemorySavedPlaceStore savedPlaceStore;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");

    @BeforeEach
    void setUp() {
        hanokListStore.clear();
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
        seedList();
    }

    @Test
    void listHanoksMatchesNormalFixtureWithSavedState() throws Exception {
        mockMvc.perform(get("/api/v1/hanoks")
                        .param("limit", "2")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.items[0].placeId").value("p-jeonju-hanok-village"))
                .andExpect(jsonPath("$.items[0].category").value("HANOK"))
                .andExpect(jsonPath("$.items[0].savedByMe").value(true))
                .andExpect(jsonPath("$.items[1].placeId").value("p-bukchon-hanok-cafe"))
                .andExpect(jsonPath("$.items[1].thumbnailUrl", nullValue()))
                .andExpect(jsonPath("$.items[1].savedByMe").value(false))
                .andExpect(jsonPath("$.nextCursor").value("r1.hanoks.cursor.2026-09-14T08:00:00Z.p-bukchon-hanok-cafe"))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void cursorKeepsFilterAndReturnsSecondPage() throws Exception {
        mockMvc.perform(get("/api/v1/hanoks")
                        .param("category", "HANOK")
                        .param("limit", "1")
                        .param("cursor", "r1.hanoks.cursor.2026-09-14T09:00:00Z.p-jeonju-hanok-village"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].placeId").value("p-gyeongju-gyochon"))
                .andExpect(jsonPath("$.nextCursor", nullValue()))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void supportedFilterWithNoResultsReturnsEmptyPage() throws Exception {
        mockMvc.perform(get("/api/v1/hanoks")
                        .param("regionCode", "kr-45-muju")
                        .param("category", "HANOK_EXPERIENCE")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor", nullValue()))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void cursorErrorsUsePublicErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/hanoks")
                        .param("cursor", "tampered")
                        .header("X-Request-Id", "req-cursor-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"))
                .andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.not("req-cursor-invalid")));

        mockMvc.perform(get("/api/v1/hanoks")
                        .param("cursor", "r1.hanoks.cursor.2026-01-01T00:00:00Z.p-old")
                        .header("X-Request-Id", "req-cursor-expired"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"))
                .andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.not("req-cursor-expired")));
    }

    @Test
    void snapshotOutageReturnsServiceUnavailableEnvelope() throws Exception {
        hanokListStore.markUnavailable();

        mockMvc.perform(get("/api/v1/hanoks")
                        .param("limit", "20")
                        .header("X-Request-Id", "req-r1-service-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Catalog data is temporarily unavailable."))
                .andExpect(jsonPath("$.details.retryAfterMs").value(30000));
    }

    private void seedList() {
        hanokListStore.add(card(
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                HanokListCategory.HANOK,
                "kr-45-jeonju",
                "전북 전주시",
                "https://cdn.onmaru.example/places/p-jeonju-hanok-village/cover.jpg",
                "한옥 골목과 전통 체험을 함께 둘러볼 수 있는 대표 한옥 권역입니다.",
                List.of("한옥", "체험", "산책"),
                Instant.parse("2026-09-14T09:00:00Z")));
        hanokListStore.add(card(
                "p-bukchon-hanok-cafe",
                "북촌 한옥 찻집",
                HanokListCategory.HANOK_CAFE,
                "kr-11-jongno",
                "서울 종로구",
                null,
                "한옥 구조를 보존한 조용한 찻집입니다.",
                List.of("카페", "북촌"),
                Instant.parse("2026-09-14T08:00:00Z")));
        hanokListStore.add(card(
                "p-gyeongju-gyochon",
                "경주 교촌 한옥마을",
                HanokListCategory.HANOK,
                "kr-47-gyeongju",
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
            String regionCode,
            String regionName,
            String thumbnailUrl,
            String summary,
            List<String> tags,
            Instant publishedAt) {
        return new HanokListProjection(
                placeId,
                name,
                category,
                regionCode,
                regionName,
                thumbnailUrl,
                summary,
                tags,
                publishedAt,
                HanokListStatus.PUBLIC);
    }
}

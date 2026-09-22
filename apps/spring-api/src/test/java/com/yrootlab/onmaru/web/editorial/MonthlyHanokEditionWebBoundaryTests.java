package com.yrootlab.onmaru.web.editorial;

import com.yrootlab.onmaru.OnMaruApplication;
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
import java.time.YearMonth;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class MonthlyHanokEditionWebBoundaryTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private InMemorySavedPlaceStore savedPlaceStore;

    @Autowired
    private InMemoryMonthlyHanokEditionStore monthlyStore;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");

    @BeforeEach
    void setUp() {
        identityStore.clear();
        savedPlaceStore.clear();
        monthlyStore.clear();
        var memberId = identityStore.createMember(clock.instant());
        identityStore.saveSession(new SessionRecord(
                hasher.hash("member-session"),
                memberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600)));
        savedPlaceStore.save(memberId, "p-jeonju-hanok-village", clock.instant(), 500);
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

    @Test
    void monthlyEditionMatchesPublishedPlacementFixture() throws Exception {
        mockMvc.perform(get("/api/v1/hanoks/monthly")
                        .param("month", "2026-09")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.month").value("2026-09"))
                .andExpect(jsonPath("$.title").value("9월의 한옥 산책"))
                .andExpect(jsonPath("$.subtitle").value("선선한 저녁에 걷기 좋은 한옥 장소를 모았습니다."))
                .andExpect(jsonPath("$.placements[0].slot").value("HERO"))
                .andExpect(jsonPath("$.placements[0].place.placeId").value("p-jeonju-hanok-village"))
                .andExpect(jsonPath("$.placements[0].place.savedByMe").value(true))
                .andExpect(jsonPath("$.placements[1].slot").value("CAFE"))
                .andExpect(jsonPath("$.placements[1].place.thumbnailUrl")
                        .value("https://tong.visitkorea.or.kr/cms/resource/04/3304404_image3_1.jpg"))
                .andExpect(jsonPath("$.placements[1].place.savedByMe").value(false));
    }

    @Test
    void missingMonthReturnsEmptyPlacements() throws Exception {
        mockMvc.perform(get("/api/v1/hanoks/monthly")
                        .param("month", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-10"))
                .andExpect(jsonPath("$.placements").isEmpty());
    }

    @Test
    void monthlyStoreOutageReturnsServiceUnavailableEnvelope() throws Exception {
        monthlyStore.markUnavailable();

        mockMvc.perform(get("/api/v1/hanoks/monthly")
                        .param("month", "2026-09")
                        .header("X-Request-Id", "req-monthly-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Catalog data is temporarily unavailable."))
                .andExpect(jsonPath("$.details.retryAfterMs").value(30000));
    }
}

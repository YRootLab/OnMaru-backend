package com.yrootlab.onmaru.web.me.timeline;

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
import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.journey.saved.odii.InMemorySavedOdiiStoryStore;
import com.yrootlab.onmaru.journey.saved.place.InMemorySavedPlaceStore;
import com.yrootlab.onmaru.journey.savedjourney.InMemorySavedJourneyStore;
import com.yrootlab.onmaru.journey.savedjourney.SavedJourneySnapshot;
import com.yrootlab.onmaru.journey.timeline.TimelineCursor;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {OnMaruApplication.class, MemberTimelineWebBoundaryTests.TestPorts.class},
        properties = {
                "onmaru.secrets.source=fake",
                "onmaru.audio.public-hosts=cdn.onmaru.example"
        })
@AutoConfigureMockMvc
class MemberTimelineWebBoundaryTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final UUID REVISION = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String STORY_ONE = "odii-story-jeonju-01";
    private static final String PLACE_ONE = "p-jeonju-hanok-village";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private InMemorySavedPlaceStore savedPlaceStore;

    @Autowired
    private InMemorySavedOdiiStoryStore savedOdiiStoryStore;

    @Autowired
    private InMemorySavedJourneyStore savedJourneyStore;

    @Autowired
    private InMemoryPlaceDetailStore placeDetailStore;

    @Autowired
    private InMemoryOdiiStoryQueryStore storyStore;

    @Autowired
    private InMemoryVisitReviewStore reviewStore;

    @Autowired
    private MemberTimelineCursorCodec cursorCodec;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    private UUID memberId;
    private UUID otherMemberId;

    @BeforeEach
    void setUp() {
        identityStore.clear();
        savedPlaceStore.clear();
        savedOdiiStoryStore.clear();
        savedJourneyStore.clear();
        placeDetailStore.clear();
        reviewStore.clear();

        memberId = member("member-session");
        otherMemberId = member("other-session");

        storyStore.replaceActive(new OdiiActiveSnapshot(REVISION, List.of(
                story(STORY_ONE, "odii-spot-jeonju-01", AudioStatus.ACTIVE, Instant.parse("2026-09-15T03:00:00Z")))));

        placeDetailStore.add(PlaceProjection.publicPlace(
                PLACE_ONE,
                "전주 한옥마을",
                "한옥",
                new RegionProjection("kr-45-jeonju", "전북 전주시"),
                "전북 전주시 완산구 기린대로 99",
                new CoordinatesProjection(35.815, 127.153),
                List.of(new ImageProjection("https://cdn.onmaru.example/places/p-jeonju-hanok-village/cover.jpg", "전주 한옥마을")),
                "공개 가능한 canonical 장소입니다.",
                List.of(),
                null));
    }

    @Test
    @DisplayName("인증되지 않은 사용자는 401 AUTH_REQUIRED 응답을 받고 Cache-Control은 no-store여야 한다")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/me/timeline").param("month", "2026-09"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.requestId", not(emptyOrNullString())));
    }

    @Test
    @DisplayName("정상적인 타임라인 조회: 4종 이벤트가 KST 월 내에서 날짜별로 그룹화되고 no-store가 적용된다")
    void normalTimelineReturnsFourEventTypesGroupedByDay() throws Exception {
        // 1. SAVED_PLACE (2026-09-14T08:10:00Z = 17:10 KST)
        savedPlaceStore.save(memberId, PLACE_ONE, Instant.parse("2026-09-14T08:10:00Z"), 300);

        // 2. SAVED_ODII_STORY (2026-09-14T07:40:00Z = 16:40 KST)
        savedOdiiStoryStore.save(memberId, STORY_ONE, Instant.parse("2026-09-14T07:40:00Z"), 300);

        // 3. SAVED_JOURNEY (2026-09-13T11:20:00Z = 20:20 KST)
        var journeySnapshot = SavedJourneySnapshot.seed(
                UUID.randomUUID(), 1, "전주 하루 여정", "jeonju", List.of(), List.of(), List.of(), Instant.parse("2026-09-13T11:20:00Z"));
        savedJourneyStore.save(memberId, journeySnapshot, Instant.parse("2026-09-13T11:20:00Z"), 20);

        // 4. WROTE_VISIT_REVIEW (2026-09-12T01:00:00Z = 10:00 KST)
        var reviewId = UUID.randomUUID();
        reviewStore.add(new VisitReviewProjection(
                reviewId,
                PLACE_ONE,
                "전주 한옥마을",
                "kr-45-jeonju",
                35.815,
                127.153,
                "한옥마을 골목길이 고즈넉하고 산책하기 정말 좋았습니다.",
                Instant.parse("2026-09-12T01:00:00Z"),
                memberId,
                Set.of(),
                VisitReviewStatus.PUBLISHED));

        mockMvc.perform(get("/api/v1/me/timeline")
                        .param("month", "2026-09")
                        .param("limit", "20")
                        .cookie(session("member-session")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.month").value("2026-09"))
                .andExpect(jsonPath("$.unavailableCount").value(0))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.groups", hasSize(3)))
                // Group 1: 2026-09-14
                .andExpect(jsonPath("$.groups[0].date").value("2026-09-14"))
                .andExpect(jsonPath("$.groups[0].items", hasSize(2)))
                .andExpect(jsonPath("$.groups[0].items[0].type").value("SAVED_PLACE"))
                .andExpect(jsonPath("$.groups[0].items[0].title").value("전주 한옥마을"))
                .andExpect(jsonPath("$.groups[0].items[0].subtitle").value("한옥 · 전북 전주시"))
                .andExpect(jsonPath("$.groups[0].items[0].thumbnailUrl").value("https://cdn.onmaru.example/places/p-jeonju-hanok-village/cover.jpg"))
                .andExpect(jsonPath("$.groups[0].items[0].target.type").value("PLACE"))
                .andExpect(jsonPath("$.groups[0].items[0].target.placeId").value(PLACE_ONE))
                .andExpect(jsonPath("$.groups[0].items[1].type").value("SAVED_ODII_STORY"))
                .andExpect(jsonPath("$.groups[0].items[1].title").value("이야기 " + STORY_ONE))
                .andExpect(jsonPath("$.groups[0].items[1].subtitle").value("오디오 이야기"))
                .andExpect(jsonPath("$.groups[0].items[1].target.type").value("ODII_STORY"))
                .andExpect(jsonPath("$.groups[0].items[1].target.storyId").value(STORY_ONE))
                // Group 2: 2026-09-13
                .andExpect(jsonPath("$.groups[1].date").value("2026-09-13"))
                .andExpect(jsonPath("$.groups[1].items", hasSize(1)))
                .andExpect(jsonPath("$.groups[1].items[0].type").value("SAVED_JOURNEY"))
                .andExpect(jsonPath("$.groups[1].items[0].title").value("전주 하루 여정"))
                .andExpect(jsonPath("$.groups[1].items[0].subtitle").value("저장한 여정"))
                .andExpect(jsonPath("$.groups[1].items[0].target.type").value("SAVED_JOURNEY"))
                // Group 3: 2026-09-12
                .andExpect(jsonPath("$.groups[2].date").value("2026-09-12"))
                .andExpect(jsonPath("$.groups[2].items", hasSize(1)))
                .andExpect(jsonPath("$.groups[2].items[0].type").value("WROTE_VISIT_REVIEW"))
                .andExpect(jsonPath("$.groups[2].items[0].title").value("전주 한옥마을"))
                .andExpect(jsonPath("$.groups[2].items[0].subtitle").value("방문 후기"))
                .andExpect(jsonPath("$.groups[2].items[0].target.type").value("VISIT_REVIEW"))
                .andExpect(jsonPath("$.groups[2].items[0].target.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.groups[2].items[0].target.placeId").value(PLACE_ONE));
    }

    @Test
    @DisplayName("비공개/삭제된 리소스는 그룹 목록에서 제외되고 unavailableCount만 1 증가한다")
    void unavailableResourceIncrementsCountOnly() throws Exception {
        // Save a non-existent place
        savedPlaceStore.save(memberId, "p-non-existent", Instant.parse("2026-09-14T08:10:00Z"), 300);

        mockMvc.perform(get("/api/v1/me/timeline")
                        .param("month", "2026-09")
                        .cookie(session("member-session")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.groups", hasSize(0)))
                .andExpect(jsonPath("$.unavailableCount").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    @DisplayName("유효하지 않은 month 파라미터는 400 VALIDATION_ERROR를 반환한다")
    void invalidMonthReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/me/timeline")
                        .param("month", "2026-13")
                        .cookie(session("member-session")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value("month"));

        mockMvc.perform(get("/api/v1/me/timeline")
                        .param("month", "invalid-month")
                        .cookie(session("member-session")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value("month"));
    }

    @Test
    @DisplayName("유효하지 않은 limit 파라미터는 400 VALIDATION_ERROR를 반환한다")
    void invalidLimitReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/me/timeline")
                        .param("limit", "0")
                        .cookie(session("member-session")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value("limit"));

        mockMvc.perform(get("/api/v1/me/timeline")
                        .param("limit", "51")
                        .cookie(session("member-session")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value("limit"));
    }

    @Test
    @DisplayName("커서 기반 페이지네이션 및 다음 페이지 조회가 올바르게 작동한다")
    void paginationWithCursorWorks() throws Exception {
        for (int i = 1; i <= 3; i++) {
            String placeId = "p-test-" + i;
            placeDetailStore.add(PlaceProjection.publicPlace(
                    placeId,
                    "장소 " + i,
                    "카테고리",
                    new RegionProjection("kr-45-jeonju", "전북 전주시"),
                    "주소 " + i,
                    new CoordinatesProjection(35.8, 127.1),
                    List.of(),
                    "설명 " + i,
                    List.of(),
                    null));
            savedPlaceStore.save(memberId, placeId, Instant.parse("2026-09-10T10:0" + i + ":00Z"), 300);
        }

        // Page 1 with limit 2
        var page1Json = mockMvc.perform(get("/api/v1/me/timeline")
                        .param("month", "2026-09")
                        .param("limit", "2")
                        .cookie(session("member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].items", hasSize(2)))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor", not(emptyOrNullString())))
                .andReturn().getResponse().getContentAsString();

        String nextCursor = OBJECT_MAPPER.readTree(page1Json).path("nextCursor").asText();

        // Page 2 with cursor
        mockMvc.perform(get("/api/v1/me/timeline")
                        .param("month", "2026-09")
                        .param("limit", "2")
                        .param("cursor", nextCursor)
                        .cookie(session("member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].items", hasSize(1)))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("타 회원의 커서로 요청 시 404 NOT_FOUND로 은닉된다")
    void otherMemberCursorReturns404() throws Exception {
        var cursor = cursorCodec.encode(new TimelineCursor(
                otherMemberId,
                YearMonth.of(2026, 9),
                20,
                Instant.parse("2026-09-15T12:00:00Z"),
                Instant.parse("2026-09-14T08:00:00Z"),
                "tl-last-id"));

        mockMvc.perform(get("/api/v1/me/timeline")
                        .param("month", "2026-09")
                        .param("cursor", cursor)
                        .cookie(session("member-session")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("변조되거나 파라미터가 불일치하는 커서는 400 CURSOR_INVALID를 반환한다")
    void invalidCursorReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/me/timeline")
                        .param("month", "2026-09")
                        .param("cursor", "malformed-cursor")
                        .cookie(session("member-session")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));

        var cursorForOtherMonth = cursorCodec.encode(new TimelineCursor(
                memberId,
                YearMonth.of(2026, 8),
                20,
                Instant.parse("2026-09-15T12:00:00Z"),
                Instant.parse("2026-09-14T08:00:00Z"),
                "tl-last-id"));

        mockMvc.perform(get("/api/v1/me/timeline")
                        .param("month", "2026-09")
                        .param("cursor", cursorForOtherMonth)
                        .cookie(session("member-session")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
    }

    private Cookie session(String rawToken) {
        return new Cookie("__Host-onmaru-session", rawToken);
    }

    private UUID member(String rawToken) {
        var id = identityStore.createMember(clock.instant());
        identityStore.saveSession(new SessionRecord(
                hasher.hash(rawToken),
                id,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(86400)));
        return id;
    }

    private static OdiiStoryProjection story(String storyId, String spotId, AudioStatus status, Instant publishedAt) {
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
        InMemoryIdentityStore testIdentityStore() {
            return new InMemoryIdentityStore();
        }

        @Bean
        @Primary
        InMemorySavedPlaceStore testSavedPlaceStore() {
            return new InMemorySavedPlaceStore();
        }

        @Bean
        @Primary
        InMemorySavedOdiiStoryStore testSavedOdiiStoryStore() {
            return new InMemorySavedOdiiStoryStore();
        }

        @Bean
        @Primary
        InMemorySavedJourneyStore testSavedJourneyStore() {
            return new InMemorySavedJourneyStore();
        }

        @Bean
        @Primary
        InMemoryPlaceDetailStore testPlaceDetailStore() {
            return new InMemoryPlaceDetailStore();
        }

        @Bean
        @Primary
        InMemoryOdiiStoryQueryStore testOdiiStoryQueryStore() {
            return new InMemoryOdiiStoryQueryStore();
        }

        @Bean
        @Primary
        InMemoryVisitReviewStore testVisitReviewStore() {
            return new InMemoryVisitReviewStore();
        }

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
}

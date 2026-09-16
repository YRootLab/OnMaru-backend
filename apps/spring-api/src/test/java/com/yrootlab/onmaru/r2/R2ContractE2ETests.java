package com.yrootlab.onmaru.r2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.audio.placelink.ApprovedAudioPlaceLink;
import com.yrootlab.onmaru.audio.placelink.ApprovedAudioPlaceLinkQuery;
import com.yrootlab.onmaru.audio.placelink.AudioPlaceLinkMatchMethod;
import com.yrootlab.onmaru.audio.placelink.CanonicalPlaceLinkCard;
import com.yrootlab.onmaru.audio.query.InMemoryOdiiStoryQueryStore;
import com.yrootlab.onmaru.audio.query.OdiiActiveSnapshot;
import com.yrootlab.onmaru.audio.query.OdiiCoordinates;
import com.yrootlab.onmaru.audio.query.OdiiRegionRef;
import com.yrootlab.onmaru.audio.query.OdiiSavedStateLookup;
import com.yrootlab.onmaru.audio.query.OdiiStoryProjection;
import com.yrootlab.onmaru.audio.query.OdiiTranscriptLine;
import com.yrootlab.onmaru.audio.query.OdiiTranscriptStatus;
import com.yrootlab.onmaru.audio.sync.AudioStatus;
import com.yrootlab.onmaru.catalog.application.query.detail.CoordinatesProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.ImageProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.RegionProjection;
import com.yrootlab.onmaru.catalog.application.query.spatial.InMemoryMapPlaceStore;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapCoordinates;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapCoverageStatus;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapDataAvailability;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceStatus;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapRegionRef;
import com.yrootlab.onmaru.community.moderation.InMemoryReviewReportStore;
import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.insights.query.Coordinates;
import com.yrootlab.onmaru.insights.query.HeatSpot;
import com.yrootlab.onmaru.insights.query.InMemoryInsightsQueryStore;
import com.yrootlab.onmaru.insights.query.Observation;
import com.yrootlab.onmaru.insights.query.RegionRef;
import com.yrootlab.onmaru.journey.saved.odii.InMemorySavedOdiiStoryStore;
import com.yrootlab.onmaru.journey.saved.place.InMemorySavedPlaceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {OnMaruApplication.class, R2ContractE2ETests.R2TestPorts.class},
        properties = {
                "onmaru.secrets.source=fake",
                "onmaru.audio.public-hosts=cdn.onmaru.example"
        })
@AutoConfigureMockMvc
class R2ContractE2ETests {

    private static final UUID REVIEW_ID = UUID.fromString("00000000-0000-0000-0000-000000000140");
    private static final UUID HIDDEN_REVIEW_ID = UUID.fromString("00000000-0000-0000-0000-000000000141");
    private static final UUID AUDIO_REVISION = UUID.fromString("10000000-0000-0000-0000-000000000140");
    private static final String PLACE_ID = "p-jeonju-hanok-village";
    private static final String SECOND_PLACE_ID = "p-jeonju-gyodong-tea";
    private static final String STORY_ID = "odii-story-jeonju-hanok-01";
    private static final String PRIVATE_STORY_ID = "odii-story-private-01";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private InMemoryMapPlaceStore mapPlaceStore;

    @Autowired
    private InMemoryPlaceDetailStore placeDetailStore;

    @Autowired
    private InMemoryVisitReviewStore visitReviewStore;

    @Autowired
    private InMemoryReviewReportStore reviewReportStore;

    @Autowired
    private InMemorySavedPlaceStore savedPlaceStore;

    @Autowired
    private InMemorySavedOdiiStoryStore savedOdiiStoryStore;

    @Autowired
    private InMemoryOdiiStoryQueryStore odiiStoryStore;

    @Autowired
    private InMemoryInsightsQueryStore insightsStore;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    private UUID authorId;

    @BeforeEach
    void setUp() {
        identityStore.clear();
        mapPlaceStore.clear();
        placeDetailStore.clear();
        visitReviewStore.clear();
        reviewReportStore.clear();
        savedPlaceStore.clear();
        savedOdiiStoryStore.clear();
        odiiStoryStore.clear();
        insightsStore.clear();

        authorId = member("author-session");
        member("member-session");
        seedPlace();
        seedMapPlaces();
        seedReviews();
        seedInsights();
        odiiStoryStore.replaceActive(new OdiiActiveSnapshot(AUDIO_REVISION, List.of(
                odiiStory(STORY_ID, AudioStatus.ACTIVE),
                odiiStory(PRIVATE_STORY_ID, AudioStatus.DELETED))));
    }

    @Test
    void publicRegionMapInsights() throws Exception {
        var regionResponse = mockMvc.perform(get("/api/v1/regions/resolve")
                        .param("lat", "35.8151")
                        .param("lng", "127.1530"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.candidates[1].region.regionCode").value("kr-45-jeonju"))
                .andReturn().getResponse().getContentAsString();
        assertR2FixtureShape("region-resolve-normal.json", regionResponse);

        var mapResponse = mockMvc.perform(get("/api/v1/map/places")
                        .param("regionCode", "kr-45-jeonju")
                        .param("language", "ko-KR")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverageStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.items[0].placeId").value(PLACE_ID))
                .andExpect(jsonPath("$.items[0].savedByMe").value(false))
                .andExpect(jsonPath("$.items[0].linkedOdiiStoryIds[0]").value(STORY_ID))
                .andExpect(jsonPath("$.items[0].contentId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertR2FixtureShape("map-places-normal.json", mapResponse);

        mockMvc.perform(get("/api/v1/visit-review-regions")
                        .param("parentRegionCode", "kr-45"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].region.regionCode").value("kr-45-jeonju"))
                .andExpect(jsonPath("$.items[0].reviewCount").value(1));

        var observationResponse = mockMvc.perform(get("/api/v1/insights/observations")
                        .param("regionCode", "kr-45-jeonju")
                        .param("metric", "VISITOR_COUNT")
                        .param("from", "2026-09-14")
                        .param("to", "2026-09-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverageStatus").value("COMPLETE"))
                .andExpect(jsonPath("$.items[0].value").value(18240))
                .andReturn().getResponse().getContentAsString();
        assertR2FixtureShape("insights-observations-normal.json", observationResponse);
        var observedDate = Instant.parse("2026-09-14T23:59:59Z");
        var generatedAt = Instant.parse(OBJECT_MAPPER.readTree(observationResponse).path("generatedAt").asText());
        org.assertj.core.api.Assertions.assertThat(generatedAt).isAfter(observedDate);

        var staleResponse = mockMvc.perform(get("/api/v1/insights/observations")
                        .param("regionCode", "kr-45-jeonju")
                        .param("metric", "VISITOR_COUNT")
                        .param("from", "2026-09-07")
                        .param("to", "2026-09-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverageStatus").value("STALE"))
                .andExpect(jsonPath("$.items[0].coverageStatus").value("STALE"))
                .andReturn().getResponse().getContentAsString();
        assertR2FixtureShape("insights-observations-stale.json", staleResponse);

        var missingResponse = mockMvc.perform(get("/api/v1/insights/heatmap")
                        .param("regionCode", "kr-45-muju")
                        .param("date", "2026-09-14")
                        .param("metric", "CONGESTION_SCORE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverageStatus").value("MISSING"))
                .andExpect(jsonPath("$.spots").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertR2FixtureShape("insights-coverage-missing.json", missingResponse);
    }

    @Test
    void memberReviewOdiiSave() throws Exception {
        var created = mockMvc.perform(post("/api/v1/places/{placeId}/visit-reviews", PLACE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  전주 처마가 좋았습니다.  \"}")
                        .cookie(sessionCookie(), csrfCookie())
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", "00000000-0000-0000-0000-000000000240"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", startsWith("/api/v1/visit-reviews/")))
                .andExpect(jsonPath("$.text").value("전주 처마가 좋았습니다."))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var createdReviewId = OBJECT_MAPPER.readTree(created).path("id").asText();

        mockMvc.perform(put("/api/v1/visit-reviews/{reviewId}/likes/me", REVIEW_ID)
                        .cookie(sessionCookie(), csrfCookie())
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likedByMe").value(true))
                .andExpect(jsonPath("$.likeCount").value(1));

        var firstReviewPage = mockMvc.perform(get("/api/v1/visit-reviews")
                        .param("scope", "REGION")
                        .param("regionCode", "kr-45-jeonju")
                        .param("limit", "1")
                        .cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(createdReviewId))
                .andExpect(jsonPath("$.nextCursor", not(emptyOrNullString())))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var cursor = OBJECT_MAPPER.readTree(firstReviewPage).path("nextCursor").asText();
        mockMvc.perform(get("/api/v1/visit-reviews")
                        .param("scope", "REGION")
                        .param("regionCode", "kr-45-jeonju")
                        .param("limit", "1")
                        .param("cursor", cursor)
                        .cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(REVIEW_ID.toString()));

        var odiiResponse = mockMvc.perform(get("/api/v1/odii/stories/{storyId}", STORY_ID)
                        .param("language", "ko-KR")
                        .cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.story.storyId").value(STORY_ID))
                .andExpect(jsonPath("$.story.linkedPlaceId").value(PLACE_ID))
                .andExpect(jsonPath("$.story.savedByMe").value(false))
                .andExpect(jsonPath("$.audioUrl").value("https://cdn.onmaru.example/odii/" + STORY_ID + ".mp3"))
                .andExpect(jsonPath("$.stid").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertR2FixtureShape("odii-story-detail-normal.json", odiiResponse);

        savePlace(PLACE_ID).andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("PLACE"))
                .andExpect(jsonPath("$.savedByMe").value(true));
        savePlace(SECOND_PLACE_ID).andExpect(status().isOk());
        saveOdii().andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("ODII_STORY"))
                .andExpect(jsonPath("$.storyId").value(STORY_ID));

        mockMvc.perform(get("/api/v1/saved-resources")
                        .param("type", "ODII_STORY")
                        .param("limit", "20")
                        .cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].storyId").value(STORY_ID))
                .andExpect(jsonPath("$.items[0].id").doesNotExist())
                .andExpect(jsonPath("$.items[0].stid").doesNotExist());

        var savedPage = mockMvc.perform(get("/api/v1/saved-resources")
                        .param("type", "PLACE")
                        .param("limit", "1")
                        .cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor", not(emptyOrNullString())))
                .andReturn().getResponse().getContentAsString();
        var savedCursor = OBJECT_MAPPER.readTree(savedPage).path("nextCursor").asText();
        mockMvc.perform(get("/api/v1/saved-resources")
                        .param("type", "PLACE")
                        .param("limit", "1")
                        .param("cursor", savedCursor)
                        .cookie(sessionCookie("author-session")))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void moderationHiddenReview() throws Exception {
        mockMvc.perform(post("/api/v1/visit-reviews/{reviewId}/reports", REVIEW_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"ABUSE\",\"detail\":\"숨김 처리 필요\"}")
                        .cookie(sessionCookie(), csrfCookie())
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", "00000000-0000-0000-0000-000000000340"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("OPEN"));

        mockMvc.perform(post("/api/v1/operations/moderation/visit-reviews/{reviewId}", REVIEW_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nextStatus\":\"HIDDEN\",\"reason\":\"PII_HIGH_RISK\"}")
                        .cookie(csrfCookie())
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Authorization", "Bearer fake-moderation-operator-token-current")
                        .header("X-OnMaru-Operator", "operator-r2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextStatus").value("HIDDEN"));

        mockMvc.perform(get("/api/v1/places/{placeId}/visit-reviews", PLACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(REVIEW_ID)).isEmpty())
                .andExpect(jsonPath("$.items[?(@.text == '재노출되면 안 되는 후기 본문')]").isEmpty());

        mockMvc.perform(get("/api/v1/visit-reviews")
                        .param("scope", "REGION")
                        .param("regionCode", "kr-45-jeonju"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(HIDDEN_REVIEW_ID)).isEmpty())
                .andExpect(jsonPath("$.items[?(@.text == '처음부터 숨김 처리된 후기 본문')]").isEmpty());

        mockMvc.perform(get("/api/v1/visit-review-regions")
                        .param("parentRegionCode", "kr-45"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reviewCount").value(0));

        mockMvc.perform(get("/api/v1/odii/stories/{storyId}", PRIVATE_STORY_ID))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/saved-resources/odii-stories/{storyId}", PRIVATE_STORY_ID)
                        .cookie(sessionCookie(), csrfCookie())
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void sourceOutage() throws Exception {
        mapPlaceStore.markUnavailable();
        mockMvc.perform(get("/api/v1/map/places")
                        .param("regionCode", "kr-45-jeonju")
                        .header("X-Request-Id", "req-r2-map-outage"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.retryAfterMs").value(30000));

        odiiStoryStore.markUnavailable();
        mockMvc.perform(get("/api/v1/odii/stories")
                        .header("X-Request-Id", "req-r2-odii-outage"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.retryAfterMs").value(30000));

        mockMvc.perform(get("/api/v1/visit-reviews")
                        .param("scope", "ALL")
                        .param("cursor", "tampered")
                        .header("X-Request-Id", "req-r2-cursor-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"))
                .andExpect(jsonPath("$.requestId").value(not("req-r2-cursor-invalid")));

        visitReviewStore.markUnavailable();
        mockMvc.perform(get("/api/v1/visit-reviews")
                        .param("scope", "ALL")
                        .header("X-Request-Id", "req-r2-review-outage"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.retryAfterMs").value(30000));

        mockMvc.perform(put("/api/v1/saved-resources/places/{placeId}", PLACE_ID)
                        .cookie(csrfCookie())
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(put("/api/v1/saved-resources/places/{placeId}", PLACE_ID)
                        .cookie(sessionCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    private void assertR2FixtureShape(String fixtureName, String runtimeJson) throws Exception {
        var fixture = Path.of("..", "..", "docs", "contracts", "fixtures", "r2", fixtureName);
        var expectedBody = OBJECT_MAPPER.readTree(fixture.toFile()).path("response").path("body");
        var runtimeBody = OBJECT_MAPPER.readTree(runtimeJson);
        assertSameShape(expectedBody, runtimeBody, "$");
        assertNoProviderKeys(runtimeBody, "$");
    }

    private void assertSameShape(JsonNode expected, JsonNode actual, String path) {
        org.assertj.core.api.Assertions.assertThat(actual).as(path).isNotNull();
        if (actual.isNull()) {
            org.assertj.core.api.Assertions.assertThat(expected.isNull()).as(path).isTrue();
            return;
        }
        if (expected.isNumber() && actual.isNumber()) {
            org.assertj.core.api.Assertions.assertThat(actual.decimalValue().compareTo(expected.decimalValue()))
                    .as(path)
                    .isZero();
            return;
        }
        org.assertj.core.api.Assertions.assertThat(actual.getNodeType()).as(path).isEqualTo(expected.getNodeType());
        if (expected.isObject()) {
            var expectedKeys = expected.propertyStream().map(java.util.Map.Entry::getKey).toList();
            var actualKeys = actual.propertyStream().map(java.util.Map.Entry::getKey).toList();
            org.assertj.core.api.Assertions.assertThat(actualKeys)
                    .as(path + " keys")
                    .containsExactlyInAnyOrderElementsOf(expectedKeys);
            expected.properties().forEach(entry ->
                    assertSameShape(entry.getValue(), actual.get(entry.getKey()), path + "." + entry.getKey()));
        } else if (expected.isArray() && !expected.isEmpty()) {
            org.assertj.core.api.Assertions.assertThat(actual).as(path).isNotEmpty();
            org.assertj.core.api.Assertions.assertThat(actual.size()).as(path + " length").isEqualTo(expected.size());
            for (int index = 0; index < expected.size(); index++) {
                assertSameShape(expected.get(index), actual.get(index), path + "[" + index + "]");
            }
        } else if (expected.isNumber() && actual.isNumber()) {
            org.assertj.core.api.Assertions.assertThat(actual.decimalValue()).as(path).isEqualByComparingTo(expected.decimalValue());
        } else if (expected.isValueNode() && !isDynamicRuntimeValue(path)) {
            org.assertj.core.api.Assertions.assertThat(actual).as(path).isEqualTo(expected);
        }
    }

    private boolean isDynamicRuntimeValue(String path) {
        return path.endsWith(".generatedAt")
                || path.endsWith(".resolvedAt");
    }

    private void assertNoProviderKeys(JsonNode node, String path) {
        if (node.isObject()) {
            var forbidden = Set.of("contentId", "contentid", "tid", "tlid", "stid", "stlid", "serviceKey");
            node.properties().forEach(entry -> {
                org.assertj.core.api.Assertions.assertThat(forbidden)
                        .as("provider key at " + path)
                        .doesNotContain(entry.getKey());
                assertNoProviderKeys(entry.getValue(), path + "." + entry.getKey());
            });
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                assertNoProviderKeys(node.get(index), path + "[" + index + "]");
            }
        }
    }

    private void seedPlace() {
        addPlaceDetail(PLACE_ID, "전주 한옥마을");
        addPlaceDetail(SECOND_PLACE_ID, "교동 찻집");
    }

    private void addPlaceDetail(String placeId, String name) {
        placeDetailStore.add(PlaceProjection.publicPlace(
                placeId,
                name,
                "한옥",
                new RegionProjection("kr-45-jeonju", "전북 전주시"),
                "전북 전주시 완산구 기린대로 99",
                new CoordinatesProjection(35.8151, 127.1530),
                List.of(new ImageProjection(
                        "https://cdn.onmaru.example/places/" + placeId + "/cover.jpg",
                        name)),
                "한옥 골목과 전통 체험을 함께 둘러볼 수 있는 대표 한옥 권역입니다.",
                List.of("한옥", "체험", "산책"),
                STORY_ID));
    }

    private void seedMapPlaces() {
        mapPlaceStore.add(new MapPlaceProjection(
                PLACE_ID,
                "전주 한옥마을",
                "한옥",
                new MapRegionRef("kr-45-jeonju", "전북 전주시", "CITY", "kr-45"),
                new MapCoordinates(35.8151, 127.1530),
                "https://cdn.onmaru.example/places/" + PLACE_ID + "/cover.jpg",
                "한옥 골목과 전통 체험을 함께 둘러볼 수 있는 대표 한옥 권역입니다.",
                List.of(STORY_ID),
                new MapDataAvailability(
                        MapCoverageStatus.COMPLETE,
                        MapCoverageStatus.PARTIAL,
                        MapCoverageStatus.COMPLETE),
                MapPlaceStatus.PUBLIC));
    }

    private void seedReviews() {
        visitReviewStore.add(review(REVIEW_ID, VisitReviewStatus.PUBLISHED, "재노출되면 안 되는 후기 본문", authorId));
        visitReviewStore.add(review(HIDDEN_REVIEW_ID, VisitReviewStatus.HIDDEN, "처음부터 숨김 처리된 후기 본문", authorId));
    }

    private void seedInsights() {
        var jeonju = new RegionRef("kr-45-jeonju", "전북 전주시", "CITY", "kr-45");
        insightsStore.save(new Observation(
                "obs-kr-45-jeonju-2026-09-14-visitors",
                jeonju,
                LocalDate.parse("2026-09-14"),
                "VISITOR_COUNT",
                18240L,
                "persons",
                "SIGUNGU",
                "COMPLETE"));
        insightsStore.save(new Observation(
                "obs-kr-45-jeonju-2026-09-07-visitors-stale",
                jeonju,
                LocalDate.parse("2026-09-07"),
                "VISITOR_COUNT",
                17320L,
                "persons",
                "SIGUNGU",
                "STALE"));
        insightsStore.save(new HeatSpot(
                "heat-p-jeonju-hanok-village-2026-09-14",
                PLACE_ID,
                "전주 한옥마을",
                jeonju,
                new Coordinates(35.8151, 127.1530),
                18240L,
                72.4,
                "BUSY",
                1.8,
                "COMPLETE",
                LocalDate.parse("2026-09-14"),
                "CONGESTION_SCORE"));
    }

    private VisitReviewProjection review(UUID reviewId, VisitReviewStatus status, String text, UUID author) {
        return new VisitReviewProjection(
                reviewId,
                PLACE_ID,
                "전주 한옥마을",
                "kr-45-jeonju",
                35.8151,
                127.1530,
                text,
                Instant.parse("2026-09-15T02:00:00Z"),
                author,
                Set.of(),
                status);
    }

    private OdiiStoryProjection odiiStory(String storyId, AudioStatus status) {
        return new OdiiStoryProjection(
                storyId,
                "odii-spot-jeonju",
                "ko-KR",
                "전주의 한옥 골목 이야기",
                "전주 한옥마을 산책",
                "한옥/고택",
                new OdiiRegionRef("kr-45-jeonju", "전북 전주시", "CITY", "kr-45"),
                new OdiiCoordinates(35.817632, 127.152948),
                185,
                "https://cdn.onmaru.example/odii/" + storyId + ".jpg",
                "https://cdn.onmaru.example/odii/" + storyId + ".mp3",
                OdiiTranscriptStatus.OFFICIAL,
                List.of(
                        new OdiiTranscriptLine(
                                0,
                                0,
                                "전주 한옥마을의 골목은 오래된 집과 생활의 기억이 함께 남아 있습니다."),
                        new OdiiTranscriptLine(1, 12.5, "천천히 걸으며 처마와 담장 사이의 소리를 들어보세요.")),
                List.of("한옥 골목", "전주 한옥마을", "처마", "담장"),
                Instant.parse("2026-09-15T02:00:00Z"),
                status,
                status);
    }

    private org.springframework.test.web.servlet.ResultActions savePlace(String placeId) throws Exception {
        return mockMvc.perform(put("/api/v1/saved-resources/places/{placeId}", placeId)
                .cookie(sessionCookie(), csrfCookie())
                .header("X-CSRF-TOKEN", "csrf-token"));
    }

    private org.springframework.test.web.servlet.ResultActions saveOdii() throws Exception {
        return mockMvc.perform(put("/api/v1/saved-resources/odii-stories/{storyId}", STORY_ID)
                .cookie(sessionCookie(), csrfCookie())
                .header("X-CSRF-TOKEN", "csrf-token"));
    }

    private UUID member(String sessionToken) {
        var id = identityStore.createMember(clock.instant());
        identityStore.saveSession(new SessionRecord(
                hasher.hash(sessionToken),
                id,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600)));
        return id;
    }

    private jakarta.servlet.http.Cookie sessionCookie() {
        return sessionCookie("member-session");
    }

    private jakarta.servlet.http.Cookie sessionCookie(String session) {
        return new jakarta.servlet.http.Cookie("__Host-onmaru-session", session);
    }

    private jakarta.servlet.http.Cookie csrfCookie() {
        return new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token");
    }

    @TestConfiguration
    static class R2TestPorts {

        @Bean
        @Primary
        InMemoryOdiiStoryQueryStore r2OdiiStoryQueryStore() {
            return new InMemoryOdiiStoryQueryStore();
        }

        @Bean
        @Primary
        InMemoryInsightsQueryStore r2InsightsQueryStore() {
            return new InMemoryInsightsQueryStore();
        }

        @Bean
        @Primary
        ApprovedAudioPlaceLinkQuery r2ApprovedAudioPlaceLinkQuery() {
            return (spotId, memberId) -> spotId.equals("odii-spot-jeonju")
                    ? Optional.of(new ApprovedAudioPlaceLink(
                            spotId,
                            AudioPlaceLinkMatchMethod.MANUAL_REFERENCE,
                            BigDecimal.ONE,
                            Instant.parse("2026-09-15T00:00:00Z"),
                            new CanonicalPlaceLinkCard(
                                    PLACE_ID,
                                    "전주 한옥마을",
                                    "한옥/고택",
                                    "전북 전주시",
                                    "https://cdn.onmaru.example/places/" + PLACE_ID + "/cover.jpg",
                                    memberId.isPresent())))
                    : Optional.empty();
        }

        @Bean
        @Primary
        OdiiSavedStateLookup r2OdiiSavedStateLookup(InMemorySavedOdiiStoryStore savedOdiiStoryStore) {
            return (memberId, storyId) -> memberId
                    .map(id -> savedOdiiStoryStore.savedBy(id, storyId))
                    .orElse(false);
        }
    }
}

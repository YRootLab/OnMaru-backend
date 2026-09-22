package com.yrootlab.onmaru.web.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.audio.placelink.ApprovedAudioPlaceLink;
import com.yrootlab.onmaru.audio.placelink.ApprovedAudioPlaceLinkQuery;
import com.yrootlab.onmaru.audio.placelink.AudioPlaceLinkMatchMethod;
import com.yrootlab.onmaru.audio.placelink.CanonicalPlaceLinkCard;
import com.yrootlab.onmaru.audio.query.OdiiActiveSnapshot;
import com.yrootlab.onmaru.audio.query.OdiiCoordinates;
import com.yrootlab.onmaru.audio.query.OdiiRegionRef;
import com.yrootlab.onmaru.audio.query.OdiiSavedStateLookup;
import com.yrootlab.onmaru.audio.query.OdiiStoryProjection;
import com.yrootlab.onmaru.audio.query.OdiiStoryQueryStore;
import com.yrootlab.onmaru.audio.query.OdiiStoryUnavailableException;
import com.yrootlab.onmaru.audio.query.OdiiTranscriptLine;
import com.yrootlab.onmaru.audio.query.OdiiTranscriptStatus;
import com.yrootlab.onmaru.audio.sync.AudioStatus;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {OnMaruApplication.class, OdiiStoryWebBoundaryTests.PortTestConfiguration.class},
        properties = {
                "onmaru.secrets.source=fake",
                "onmaru.audio.public-hosts=cdn.onmaru.example"
        })
@AutoConfigureMockMvc
class OdiiStoryWebBoundaryTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final UUID REVISION_ONE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID REVISION_TWO = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestOdiiStoryQueryStore store;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private Clock clock;

    private final TokenHasher tokenHasher = new TokenHasher("fake-oauth-client-secret-current");

    @BeforeEach
    void setUp() {
        identityStore.clear();
        store.replaceActive(snapshot(REVISION_ONE,
                story("odii-story-jeonju-hanok-01", "ko-KR", OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE),
                story("odii-story-jeonju-hanok-01", "en-US", OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE)));
    }

    @Test
    void listAndDetailMatchThePublicContractWithoutProviderIdentifiers() throws Exception {
        mockMvc.perform(get("/api/v1/odii/stories")
                        .param("language", "ko-KR")
                        .param("category", "한옥/고택")
                        .param("regionCode", "kr-45-jeonju")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.coverageStatus").value("COMPLETE"))
                .andExpect(jsonPath("$.language").value("ko-KR"))
                .andExpect(jsonPath("$.languageStatus").value("EXACT"))
                .andExpect(jsonPath("$.items[0].storyId").value("odii-story-jeonju-hanok-01"))
                .andExpect(jsonPath("$.items[0].category").value("한옥/고택"))
                .andExpect(jsonPath("$.items[0].region.regionCode").value("kr-45-jeonju"))
                .andExpect(jsonPath("$.items[0].coordinates.lat").value(35.817632))
                .andExpect(jsonPath("$.items[0].durationSeconds").value(185))
                .andExpect(jsonPath("$.items[0].linkedPlaceId").value("p-jeonju-hanok-village"))
                .andExpect(jsonPath("$.items[0].contentTags", hasItem("한옥 골목")))
                .andExpect(jsonPath("$.items[0].savedByMe").value(false))
                .andExpect(jsonPath("$.items[0].spotId").doesNotExist())
                .andExpect(jsonPath("$.items[0].stid").doesNotExist())
                .andExpect(jsonPath("$.nextCursor", nullValue()))
                .andExpect(jsonPath("$.hasMore").value(false));

        mockMvc.perform(get("/api/v1/odii/stories/odii-story-jeonju-hanok-01")
                        .param("language", "ko-KR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.transcriptStatus").value("OFFICIAL"))
                .andExpect(jsonPath("$.story.storyId").value("odii-story-jeonju-hanok-01"))
                .andExpect(jsonPath("$.story.contentTags", hasItem("한옥 골목")))
                .andExpect(jsonPath("$.audioUrl")
                        .value("https://cdn.onmaru.example/odii/odii-story-jeonju-hanok-01.mp3"))
                .andExpect(jsonPath("$.transcript[1].startSecond").value(12.5))
                .andExpect(jsonPath("$.stid").doesNotExist())
                .andExpect(jsonPath("$.serviceKey").doesNotExist());
    }

    @Test
    void regionGroupsExposeBroadRegionCountsAndCompatibilityAlias() throws Exception {
        mockMvc.perform(get("/api/v1/odii/regions")
                        .param("language", "ko-KR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.language").value("ko-KR"))
                .andExpect(jsonPath("$.languageStatus").value("EXACT"))
                .andExpect(jsonPath("$.groups[0].label").value("전북"))
                .andExpect(jsonPath("$.groups[0].regionCodes[0]").value("kr-45"))
                .andExpect(jsonPath("$.groups[0].storyCount").value(1));

        mockMvc.perform(get("/api/v1/audio/regions")
                        .param("language", "ko-KR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].storyCount").value(1));

        mockMvc.perform(get("/api/v1/odii/regions")
                        .param("language", "invalid-LANG"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void resolvesSavedStateFromTheOptionalServerSessionOnly() throws Exception {
        var memberId = identityStore.createMember(clock.instant());
        identityStore.saveSession(new SessionRecord(
                tokenHasher.hash("member-session"),
                memberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600)));

        mockMvc.perform(get("/api/v1/odii/stories")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].savedByMe").value(true));
    }

    @Test
    void reportsKoreanFallbackAndMissingTranscriptExplicitly() throws Exception {
        store.replaceActive(snapshot(REVISION_ONE,
                story("odii-story-jeonju-hanok-01", "ko-KR", OdiiTranscriptStatus.MISSING, AudioStatus.ACTIVE)));

        mockMvc.perform(get("/api/v1/odii/stories/odii-story-jeonju-hanok-01")
                        .param("language", "en-US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverageStatus").value("MISSING"))
                .andExpect(jsonPath("$.language").value("ko-KR"))
                .andExpect(jsonPath("$.languageStatus").value("FALLBACK"))
                .andExpect(jsonPath("$.transcriptStatus").value("MISSING"))
                .andExpect(jsonPath("$.transcript").isEmpty());
    }

    @Test
    void hidesNonPublicTombstonedAndUnknownStoriesBehindNotFound() throws Exception {
        store.replaceActive(snapshot(REVISION_ONE,
                story("odii-story-hidden-01", "ko-KR", OdiiTranscriptStatus.OFFICIAL, AudioStatus.HIDDEN),
                story("odii-story-deleted-01", "ko-KR", OdiiTranscriptStatus.OFFICIAL, AudioStatus.DELETED)));

        mockMvc.perform(get("/api/v1/odii/stories/odii-story-hidden-01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("The requested Odii story is not available."))
                .andExpect(jsonPath("$.details.resourceType").value("ODII_STORY"));
        mockMvc.perform(get("/api/v1/odii/stories/odii-story-deleted-01"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/odii/stories/odii-story-unknown-01"))
                .andExpect(status().isNotFound());
    }

    @Test
    void translatesInvalidExpiredAndUnavailableQueriesToPublicErrors() throws Exception {
        mockMvc.perform(get("/api/v1/odii/stories")
                        .param("language", "english")
                        .header("X-Request-Id", "req-odii-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.requestId")
                        .value(org.hamcrest.Matchers.not("req-odii-invalid")))
                .andExpect(jsonPath("$.details.field").value("language"));
        mockMvc.perform(get("/api/v1/odii/stories")
                        .param("limit", "not-a-number")
                        .header("X-Request-Id", "req-odii-limit-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.requestId")
                        .value(org.hamcrest.Matchers.not("req-odii-limit-invalid")))
                .andExpect(jsonPath("$.details.field").value("limit"));

        store.replaceActive(snapshot(REVISION_ONE,
                story("odii-story-jeonju-hanok-01", "ko-KR", OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE),
                story("odii-story-gyeongju-01", "ko-KR", OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE)));
        var pageJson = mockMvc.perform(get("/api/v1/odii/stories").param("limit", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var cursor = OBJECT_MAPPER.readTree(pageJson).path("nextCursor").asText();
        var signatureStart = cursor.lastIndexOf('.') + 1;
        var tamperedCursor = cursor.substring(0, signatureStart)
                + (cursor.charAt(signatureStart) == 'A' ? 'B' : 'A')
                + cursor.substring(signatureStart + 1);
        mockMvc.perform(get("/api/v1/odii/stories")
                        .param("limit", "1")
                        .param("cursor", tamperedCursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
        mockMvc.perform(get("/api/v1/odii/stories")
                        .param("limit", "2")
                        .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
        store.replaceActive(snapshot(REVISION_TWO,
                story("odii-story-gyeongju-01", "ko-KR", OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE)));
        mockMvc.perform(get("/api/v1/odii/stories").param("limit", "1").param("cursor", cursor))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));

        store.markUnavailable();
        mockMvc.perform(get("/api/v1/odii/stories")
                        .header("X-Request-Id", "req-odii-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Odii data is temporarily unavailable."))
                .andExpect(jsonPath("$.details.retryAfterMs").value(30000));
    }

    private OdiiActiveSnapshot snapshot(UUID revisionId, OdiiStoryProjection... stories) {
        return new OdiiActiveSnapshot(revisionId, List.of(stories));
    }

    private OdiiStoryProjection story(
            String storyId,
            String language,
            OdiiTranscriptStatus transcriptStatus,
            AudioStatus status) {
        var transcript = transcriptStatus == OdiiTranscriptStatus.MISSING
                ? List.<OdiiTranscriptLine>of()
                : List.of(
                        new OdiiTranscriptLine(0, 0, "전주 한옥마을의 골목 이야기입니다."),
                        new OdiiTranscriptLine(1, 12.5, "처마와 담장 사이의 소리를 들어보세요."));
        return new OdiiStoryProjection(
                storyId,
                "odii-spot-jeonju",
                language,
                "전주의 한옥 골목 이야기",
                "전주 한옥마을 산책",
                "한옥/고택",
                new OdiiRegionRef("kr-45-jeonju", "전북 전주시", "CITY", "kr-45"),
                new OdiiCoordinates(35.817632, 127.152948),
                185,
                "https://cdn.onmaru.example/odii/" + storyId + ".jpg",
                "https://cdn.onmaru.example/odii/" + storyId + ".mp3",
                transcriptStatus,
                transcript,
                List.of(),
                Instant.parse("2026-09-15T02:00:00Z"),
                status,
                status);
    }

    @TestConfiguration
    static class PortTestConfiguration {

        @Bean
        @Primary
        TestOdiiStoryQueryStore testOdiiStoryQueryStore() {
            return new TestOdiiStoryQueryStore();
        }

        @Bean
        @Primary
        ApprovedAudioPlaceLinkQuery testApprovedAudioPlaceLinkQuery() {
            return (spotId, memberId) -> spotId.equals("odii-spot-jeonju")
                    ? Optional.of(new ApprovedAudioPlaceLink(
                            spotId,
                            AudioPlaceLinkMatchMethod.MANUAL_REFERENCE,
                            BigDecimal.ONE,
                            Instant.parse("2026-09-15T00:00:00Z"),
                            new CanonicalPlaceLinkCard(
                                    "p-jeonju-hanok-village",
                                    "전주 한옥마을",
                                    "한옥/고택",
                                    "전북 전주시",
                                    "https://cdn.onmaru.example/places/p-jeonju-hanok-village/cover.jpg",
                                    memberId.isPresent())))
                    : Optional.empty();
        }

        @Bean
        @Primary
        OdiiSavedStateLookup testOdiiSavedStateLookup() {
            return (memberId, storyId) -> memberId.isPresent()
                    && storyId.equals("odii-story-jeonju-hanok-01");
        }
    }

    static final class TestOdiiStoryQueryStore implements OdiiStoryQueryStore {

        private volatile OdiiActiveSnapshot snapshot;
        private volatile boolean unavailable;

        @Override
        public OdiiActiveSnapshot activeSnapshot() {
            if (unavailable || snapshot == null) {
                throw new OdiiStoryUnavailableException();
            }
            return snapshot;
        }

        void replaceActive(OdiiActiveSnapshot replacement) {
            snapshot = replacement;
            unavailable = false;
        }

        void markUnavailable() {
            unavailable = true;
        }
    }
}

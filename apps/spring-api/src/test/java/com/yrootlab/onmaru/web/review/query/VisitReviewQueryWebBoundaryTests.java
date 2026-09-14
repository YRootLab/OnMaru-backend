package com.yrootlab.onmaru.web.review.query;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class VisitReviewQueryWebBoundaryTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryVisitReviewStore visitReviewStore;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    private UUID memberId;
    private UUID otherMemberId;

    @BeforeEach
    void setUp() {
        visitReviewStore.clear();
        identityStore.clear();
        memberId = identityStore.createMember(clock.instant());
        otherMemberId = identityStore.createMember(clock.instant());
        identityStore.saveSession(new SessionRecord(
                hasher.hash("member-session"),
                memberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600)));
        seedReviews();
    }

    @Test
    void listAllVisitReviewsWithMineAndLikedByMe() throws Exception {
        mockMvc.perform(get("/api/v1/visit-reviews")
                        .param("scope", "ALL")
                        .param("limit", "2")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.queryKey").value("ALL"))
                .andExpect(jsonPath("$.items[0].id").value("00000000-0000-0000-0000-000000000003"))
                .andExpect(jsonPath("$.items[0].mine").value(true))
                .andExpect(jsonPath("$.items[0].likedByMe").value(true))
                .andExpect(jsonPath("$.items[0].likeCount").value(1))
                .andExpect(jsonPath("$.items[1].id").value("00000000-0000-0000-0000-000000000002"))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andExpect(jsonPath("$.coverage.status").value("SUPPORTED"));
    }

    @Test
    void cursorReturnsSecondRegionPage() throws Exception {
        var firstResponse = mockMvc.perform(get("/api/v1/visit-reviews")
                        .param("scope", "REGION")
                        .param("regionCode", "kr-45-jeonju")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("00000000-0000-0000-0000-000000000002"))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var cursor = firstResponse.replaceAll("(?s).*\"nextCursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/v1/visit-reviews")
                        .param("scope", "REGION")
                        .param("regionCode", "kr-45-jeonju")
                        .param("limit", "1")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.nextCursor", nullValue()))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void listPlaceVisitReviewsUsesPlaceQueryKey() throws Exception {
        mockMvc.perform(get("/api/v1/places/p-bukchon-hanok-cafe/visit-reviews")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryKey").value("PLACE:p-bukchon-hanok-cafe"))
                .andExpect(jsonPath("$.items[0].placeId").value("p-bukchon-hanok-cafe"))
                .andExpect(jsonPath("$.items[0].lat").value(37.5824))
                .andExpect(jsonPath("$.items[0].lng").value(126.9836));
    }

    @Test
    void unsupportedScopeAndCursorErrorsReturnPublicEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/visit-reviews")
                        .param("scope", "NEARBY")
                        .param("limit", "20")
                        .header("X-Request-Id", "req-review-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.requestId").value("req-review-invalid"))
                .andExpect(jsonPath("$.details.field").value("scope"));

        mockMvc.perform(get("/api/v1/visit-reviews")
                        .param("scope", "ALL")
                        .param("cursor", "tampered")
                        .header("X-Request-Id", "req-review-cursor-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"))
                .andExpect(jsonPath("$.requestId").value("req-review-cursor-invalid"));
    }

    private void seedReviews() {
        visitReviewStore.add(review("00000000-0000-0000-0000-000000000001", "p-jeonju-hanok-village",
                "kr-45-jeonju", Instant.parse("2026-09-15T01:00:00Z"), otherMemberId));
        visitReviewStore.add(review("00000000-0000-0000-0000-000000000002", "p-jeonju-hanok-village",
                "kr-45-jeonju", Instant.parse("2026-09-15T02:00:00Z"), otherMemberId));
        visitReviewStore.add(review("00000000-0000-0000-0000-000000000003", "p-bukchon-hanok-cafe",
                "kr-11-jongno", Instant.parse("2026-09-15T03:00:00Z"), memberId, memberId));
        visitReviewStore.add(review("00000000-0000-0000-0000-000000000004", "p-hidden",
                "kr-45-jeonju", Instant.parse("2026-09-15T04:00:00Z"), otherMemberId).hidden());
    }

    private VisitReviewProjection review(
            String reviewId,
            String placeId,
            String regionCode,
            Instant createdAt,
            UUID authorId,
            UUID... likedBy) {
        return new VisitReviewProjection(
                UUID.fromString(reviewId),
                placeId,
                placeId.equals("p-jeonju-hanok-village") ? "전주 한옥마을" : "북촌 한옥 찻집",
                regionCode,
                placeId.equals("p-jeonju-hanok-village") ? 35.8151 : 37.5824,
                placeId.equals("p-jeonju-hanok-village") ? 127.1530 : 126.9836,
                "비 오는 날 처마 밑에서 쉬기 좋았습니다.",
                createdAt,
                authorId,
                Set.of(likedBy),
                VisitReviewStatus.PUBLISHED);
    }
}

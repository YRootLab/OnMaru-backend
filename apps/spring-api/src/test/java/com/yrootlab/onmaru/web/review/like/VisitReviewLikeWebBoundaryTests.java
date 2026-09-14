package com.yrootlab.onmaru.web.review.like;

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
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class VisitReviewLikeWebBoundaryTests {

    private static final UUID REVIEW_ID = UUID.fromString("00000000-0000-0000-0000-000000000122");
    private static final UUID HIDDEN_REVIEW_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private InMemoryVisitReviewStore visitReviewStore;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    private UUID authorId;
    private UUID memberId;

    @BeforeEach
    void setUp() {
        identityStore.clear();
        visitReviewStore.clear();
        authorId = identityStore.createMember(clock.instant());
        memberId = identityStore.createMember(clock.instant());
        identityStore.saveSession(session("author-session", authorId));
        identityStore.saveSession(session("member-session", memberId));
        visitReviewStore.add(review(REVIEW_ID, VisitReviewStatus.PUBLISHED, Set.of()));
        visitReviewStore.add(review(HIDDEN_REVIEW_ID, VisitReviewStatus.HIDDEN, Set.of()));
    }

    @Test
    void putAndDeleteLikeAreDesiredStateOperations() throws Exception {
        like(REVIEW_ID, "member-session")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.likedByMe").value(true))
                .andExpect(jsonPath("$.likeCount").value(1));
        like(REVIEW_ID, "member-session")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likedByMe").value(true))
                .andExpect(jsonPath("$.likeCount").value(1));

        unlike(REVIEW_ID, "member-session")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likedByMe").value(false))
                .andExpect(jsonPath("$.likeCount").value(0));
        unlike(REVIEW_ID, "member-session")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likedByMe").value(false))
                .andExpect(jsonPath("$.likeCount").value(0));
    }

    @Test
    void selfLikeIsForbiddenAndHiddenReviewIsNotFound() throws Exception {
        like(REVIEW_ID, "author-session")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_LIKE_FORBIDDEN"));

        like(HIDDEN_REVIEW_ID, "member-session")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void likeRequiresAuthenticatedMember() throws Exception {
        mockMvc.perform(put("/api/v1/visit-reviews/{reviewId}/likes/me", REVIEW_ID)
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    private org.springframework.test.web.servlet.ResultActions like(UUID reviewId, String sessionToken) throws Exception {
        return mockMvc.perform(put("/api/v1/visit-reviews/{reviewId}/likes/me", reviewId)
                .cookie(
                        new jakarta.servlet.http.Cookie("__Host-onmaru-session", sessionToken),
                        new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                .header("X-CSRF-TOKEN", "csrf-token"));
    }

    private org.springframework.test.web.servlet.ResultActions unlike(UUID reviewId, String sessionToken) throws Exception {
        return mockMvc.perform(delete("/api/v1/visit-reviews/{reviewId}/likes/me", reviewId)
                .cookie(
                        new jakarta.servlet.http.Cookie("__Host-onmaru-session", sessionToken),
                        new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                .header("X-CSRF-TOKEN", "csrf-token"));
    }

    private VisitReviewProjection review(UUID reviewId, VisitReviewStatus status, Set<UUID> likedBy) {
        return new VisitReviewProjection(
                reviewId,
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                "kr-45-jeonju",
                35.8151,
                127.1530,
                "비 오는 날 처마 밑에서 쉬기 좋았습니다.",
                Instant.parse("2026-09-15T02:00:00Z"),
                authorId,
                likedBy,
                status);
    }

    private SessionRecord session(String token, UUID id) {
        return new SessionRecord(
                hasher.hash(token),
                id,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600));
    }
}

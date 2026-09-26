package com.yrootlab.onmaru.web.review.command;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class VisitReviewCommandWebBoundaryTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private InMemoryVisitReviewStore visitReviewStore;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");

    @BeforeEach
    void setUp() {
        identityStore.clear();
        visitReviewStore.clear();
        var memberId = identityStore.createMember(clock.instant());
        identityStore.saveSession(session("member-session", memberId));
        var otherMemberId = identityStore.createMember(clock.instant());
        identityStore.saveSession(session("other-session", otherMemberId));
    }

    @Test
    void createReviewNormalizesTextAndReplaysSamePostOnce() throws Exception {
        var response = create("00000000-0000-0000-0000-000000000118", "  전주\r\n처마가 좋았습니다.  ", "한적", 5, "고즈넉함", "처마")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("/api/v1/visit-reviews/")))
                .andExpect(jsonPath("$.placeId").value("p-jeonju-hanok-village"))
                .andExpect(jsonPath("$.text").value("전주\n처마가 좋았습니다."))
                .andExpect(jsonPath("$.mood").value("한적"))
                .andExpect(jsonPath("$.score").value(5))
                .andExpect(jsonPath("$.tags[0]").value("고즈넉함"))
                .andExpect(jsonPath("$.mine").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var reviewId = response.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");

        create("00000000-0000-0000-0000-000000000118", "  전주\r\n처마가 좋았습니다.  ", "한적", 5, "고즈넉함", "처마")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(reviewId));

        mockMvc.perform(get("/api/v1/places/p-jeonju-hanok-village/visit-reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].mood").value("한적"))
                .andExpect(jsonPath("$.items[0].score").value(5))
                .andExpect(jsonPath("$.items[0].tags[1]").value("처마"));
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadConflicts() throws Exception {
        create("00000000-0000-0000-0000-000000000119", "좋았습니다.").andExpect(status().isCreated());

        create("00000000-0000-0000-0000-000000000119", "다른 후기입니다.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void createValidatesTextAuthenticationAndPlaceEligibility() throws Exception {
        create("00000000-0000-0000-0000-000000000120", "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value("text"));

        create("00000000-0000-0000-0000-000000000125", "좋았습니다.", "조용", 5)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field").value("mood"));

        create("00000000-0000-0000-0000-000000000126", "좋았습니다.", "한적", 6)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field").value("score"));

        mockMvc.perform(post("/api/v1/places/p-jeonju-hanok-village/visit-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"좋았습니다.\"}")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", "00000000-0000-0000-0000-000000000121"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(post("/api/v1/places/p-private-place/visit-reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"좋았습니다.\"}")
                        .cookie(
                                new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session"),
                                new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", "00000000-0000-0000-0000-000000000122"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void deleteOwnReviewAndHideOthersAsNotFound() throws Exception {
        var response = create("00000000-0000-0000-0000-000000000123", "좋았습니다.")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var reviewId = response.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(delete("/api/v1/visit-reviews/" + reviewId)
                        .cookie(
                                new jakarta.servlet.http.Cookie("__Host-onmaru-session", "other-session"),
                                new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/visit-reviews/" + reviewId)
                        .cookie(
                                new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session"),
                                new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    private org.springframework.test.web.servlet.ResultActions create(String key, String text) throws Exception {
        return create(key, text, null, null);
    }

    private org.springframework.test.web.servlet.ResultActions create(String key, String text, String mood, Integer score, String... tags) throws Exception {
        var payload = "{\"text\":\"" + text.replace("\r", "\\r").replace("\n", "\\n") + "\""
                + (mood == null ? "" : ",\"mood\":\"" + mood + "\"")
                + (score == null ? "" : ",\"score\":" + score)
                + (tags.length == 0 ? "" : ",\"tags\":[\"" + String.join("\",\"", tags) + "\"]")
                + "}";
        return mockMvc.perform(post("/api/v1/places/p-jeonju-hanok-village/visit-reviews")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .cookie(
                        new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session"),
                        new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                .header("X-CSRF-TOKEN", "csrf-token")
                .header("Idempotency-Key", key));
    }

    private SessionRecord session(String token, UUID memberId) {
        return new SessionRecord(
                hasher.hash(token),
                memberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600));
    }
}

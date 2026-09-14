package com.yrootlab.onmaru.web.review.moderation;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.community.moderation.InMemoryReviewReportStore;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class VisitReviewModerationWebBoundaryTests {

    private static final UUID REVIEW_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private InMemoryVisitReviewStore visitReviewStore;

    @Autowired
    private InMemoryReviewReportStore reviewReportStore;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    private UUID authorId;
    private UUID reporterId;

    @BeforeEach
    void setUp() {
        identityStore.clear();
        visitReviewStore.clear();
        reviewReportStore.clear();
        authorId = identityStore.createMember(clock.instant());
        reporterId = identityStore.createMember(clock.instant());
        identityStore.saveSession(session("author-session", authorId));
        identityStore.saveSession(session("reporter-session", reporterId));
        visitReviewStore.add(review(VisitReviewStatus.PUBLISHED));
    }

    @Test
    void reportReviewDedupesOpenReportForSameReporter() throws Exception {
        var response = report("reporter-session", "00000000-0000-0000-0000-000000000123", "SPAM", "반복 홍보")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var reportId = response.replaceAll("(?s).*\"reportId\":\"([^\"]+)\".*", "$1");

        report("reporter-session", "00000000-0000-0000-0000-000000000124", "ABUSE", "다른 사유")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reportId").value(reportId));
    }

    @Test
    void selfReportInvalidReportAndMissingAuthUsePublicErrors() throws Exception {
        report("author-session", "00000000-0000-0000-0000-000000000125", "SPAM", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_REPORT_FORBIDDEN"));

        report("reporter-session", "00000000-0000-0000-0000-000000000126", null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value("reason"));

        mockMvc.perform(post("/api/v1/visit-reviews/{reviewId}/reports", REVIEW_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SPAM\"}")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", "00000000-0000-0000-0000-000000000127"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void operatorModerationWritesAuditAndChangesReviewStatus() throws Exception {
        mockMvc.perform(post("/api/v1/operations/moderation/visit-reviews/{reviewId}", REVIEW_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nextStatus\":\"HIDDEN\",\"reason\":\"PII_HIGH_RISK\"}")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("X-OnMaru-Operator", "operator-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorRef").value("operator-1"))
                .andExpect(jsonPath("$.previousStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.nextStatus").value("HIDDEN"))
                .andExpect(jsonPath("$.reason").value("PII_HIGH_RISK"));
    }

    private org.springframework.test.web.servlet.ResultActions report(
            String sessionToken,
            String idempotencyKey,
            String reason,
            String detail) throws Exception {
        var body = reason == null
                ? "{}"
                : detail == null ? "{\"reason\":\"" + reason + "\"}"
                : "{\"reason\":\"" + reason + "\",\"detail\":\"" + detail + "\"}";
        return mockMvc.perform(post("/api/v1/visit-reviews/{reviewId}/reports", REVIEW_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .cookie(
                        new jakarta.servlet.http.Cookie("__Host-onmaru-session", sessionToken),
                        new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                .header("X-CSRF-TOKEN", "csrf-token")
                .header("Idempotency-Key", idempotencyKey));
    }

    private VisitReviewProjection review(VisitReviewStatus status) {
        return new VisitReviewProjection(
                REVIEW_ID,
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                "kr-45-jeonju",
                35.8151,
                127.1530,
                "비 오는 날 처마 밑에서 쉬기 좋았습니다.",
                Instant.parse("2026-09-15T02:00:00Z"),
                authorId,
                Set.of(),
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

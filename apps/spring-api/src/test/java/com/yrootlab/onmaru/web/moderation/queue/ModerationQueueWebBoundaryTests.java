package com.yrootlab.onmaru.web.moderation.queue;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.community.moderation.CreateReviewReportCommand;
import com.yrootlab.onmaru.community.moderation.InMemoryReviewReportStore;
import com.yrootlab.onmaru.community.moderation.ReviewReportReason;
import com.yrootlab.onmaru.community.moderation.VisitReviewModerationService;
import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class ModerationQueueWebBoundaryTests {

    private static final UUID REVIEW_ID = UUID.fromString("00000000-0000-0000-0000-000000000139");
    private static final UUID AUTHOR_ID = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");
    private static final UUID REPORTER_ID = UUID.fromString("8ce13b1d-01bb-42ea-8457-5e0df299a5de");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryVisitReviewStore reviewStore;

    @Autowired
    private InMemoryReviewReportStore reportStore;

    @Autowired
    private VisitReviewModerationService moderationService;

    @BeforeEach
    void setUp() {
        reviewStore.clear();
        reportStore.clear();
        reviewStore.add(new VisitReviewProjection(
                REVIEW_ID,
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                "kr-45-jeonju",
                35.8151,
                127.1530,
                "synthetic queue review",
                Instant.parse("2026-09-15T02:00:00Z"),
                AUTHOR_ID,
                Set.of(),
                VisitReviewStatus.PUBLISHED));
        moderationService.report(
                REPORTER_ID,
                REVIEW_ID,
                new CreateReviewReportCommand(ReviewReportReason.SPAM, "synthetic report detail"));
    }

    @Test
    void currentAndPreviousTokensReadProtectedQueueWithoutReporterIdentity() throws Exception {
        queue("Bearer fake-moderation-operator-token-current", "operator-1")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.items[0].reviewId").value(REVIEW_ID.toString()))
                .andExpect(jsonPath("$.items[0].reports[0].reason").value("SPAM"))
                .andExpect(jsonPath("$.items[0]", not(hasKey("reporterMemberId"))))
                .andExpect(jsonPath("$.items[0].reports[0]", not(hasKey("reporterMemberId"))));

        queue("Bearer fake-moderation-operator-token-previous", "operator-2")
                .andExpect(status().isOk());
    }

    @Test
    void missingCredentialOrActorReturnsUnauthorized() throws Exception {
        queue(null, "operator-1")
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        queue("Bearer fake-moderation-operator-token-current", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void malformedOrInvalidCredentialReturnsForbidden() throws Exception {
        queue("Basic fake-moderation-operator-token-current", "operator-1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OPERATOR_FORBIDDEN"));
        queue("Bearer wrong", "operator-1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OPERATOR_FORBIDDEN"));
    }

    private org.springframework.test.web.servlet.ResultActions queue(String authorization, String actor) throws Exception {
        var request = get("/api/v1/operations/moderation/queue");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (actor != null) {
            request.header("X-OnMaru-Operator", actor);
        }
        return mockMvc.perform(request);
    }
}

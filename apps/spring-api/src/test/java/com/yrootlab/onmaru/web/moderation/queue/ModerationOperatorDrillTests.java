package com.yrootlab.onmaru.web.moderation.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.community.moderation.InMemoryReviewReportStore;
import com.yrootlab.onmaru.community.moderation.ModerationActorType;
import com.yrootlab.onmaru.community.moderation.ModerationReason;
import com.yrootlab.onmaru.community.moderation.ReviewReportStatus;
import com.yrootlab.onmaru.community.moderation.VisitReviewModerationService;
import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.observability.InMemoryTelemetrySink;
import com.yrootlab.onmaru.observability.TelemetryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
@Import(ModerationOperatorDrillTests.TelemetryTestConfig.class)
class ModerationOperatorDrillTests {

    private static final Path FIXTURE = Path.of(
            "..", "..", "testing", "e2e", "moderation", "operator-drill.json");
    private static final String CURRENT_TOKEN = "fake-moderation-operator-token-current";
    private static final String PREVIOUS_TOKEN = "fake-moderation-operator-token-previous";
    private static final String ACTOR_REF = "oncall-139";
    private static final String CSRF = "synthetic-csrf";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private InMemoryVisitReviewStore reviewStore;

    @Autowired
    private InMemoryReviewReportStore reportStore;

    @Autowired
    private VisitReviewModerationService moderationService;

    @Autowired
    private InMemoryTelemetrySink telemetrySink;

    @Autowired
    private Clock clock;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    private final Set<String> sensitiveMemberIds = new HashSet<>();
    private DrillFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        identityStore.clear();
        reviewStore.clear();
        reportStore.clear();
        telemetrySink.clear();
        sensitiveMemberIds.clear();
        fixture = objectMapper.readValue(Files.readString(FIXTURE), DrillFixture.class);
        for (DrillCase drillCase : fixture.cases()) {
            UUID authorId = identityStore.createMember(clock.instant());
            UUID reporterId = identityStore.createMember(clock.instant());
            sensitiveMemberIds.add(authorId.toString());
            sensitiveMemberIds.add(reporterId.toString());
            identityStore.saveSession(session(drillCase.session(), reporterId));
            reviewStore.add(review(drillCase, authorId));
        }
    }

    @Test
    void completesSyntheticReportHideRestoreRemoveDrillWithoutPublicOrTelemetryLeakage() throws Exception {
        for (int index = 0; index < fixture.cases().size(); index++) {
            DrillCase drillCase = fixture.cases().get(index);
            report(drillCase, idempotencyKey(index + 1))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("OPEN"));
        }

        DrillCase duplicate = fixture.caseByAction("DUPLICATE");
        String originalReportId = report(duplicate, idempotencyKey(10))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()
                .replaceAll("(?s).*\"reportId\":\"([^\"]+)\".*", "$1");
        report(duplicate, idempotencyKey(11))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reportId").value(originalReportId));

        DrillCase pii = fixture.caseByAction("RESTORE");
        moderationService.hideHighRiskPii(pii.reviewId(), "pii-detector-v1");
        assertPublicReviewsExclude(pii.text());

        mockMvc.perform(get("/api/v1/operations/moderation/queue")
                        .header("Authorization", "Bearer " + CURRENT_TOKEN)
                        .header("X-OnMaru-Operator", ACTOR_REF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reviewId").value(pii.reviewId().toString()))
                .andExpect(jsonPath("$.items[0].priority").value("HIGH_RISK"))
                .andExpect(jsonPath("$..reporterMemberId").doesNotExist());

        moderate(pii, "PUBLISHED", "FALSE_POSITIVE", PREVIOUS_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorRef").value(ACTOR_REF));
        assertPublicReviewsInclude(pii.text());

        DrillCase remove = fixture.caseByAction("REMOVE");
        moderate(remove, "REMOVED", "ABUSE_CONFIRMED", CURRENT_TOKEN)
                .andExpect(status().isOk());
        assertPublicReviewsExclude(remove.text());

        List<DrillCase> standardDismissals = fixture.cases().stream()
                .filter(drillCase -> !Set.of("RESTORE", "REMOVE").contains(drillCase.action()))
                .toList();
        for (DrillCase standard : standardDismissals) {
            moderate(standard, "PUBLISHED", "FALSE_POSITIVE", CURRENT_TOKEN)
                    .andExpect(status().isOk());
            assertPublicReviewsInclude(standard.text());
        }

        assertThat(reportStore.reports()).hasSize(fixture.cases().size());
        assertThat(reportStore.openReports()).isEmpty();
        assertThat(reportStore.reports())
                .filteredOn(report -> report.reviewId().equals(pii.reviewId()))
                .allMatch(report -> report.status() == ReviewReportStatus.DISMISSED);
        assertThat(reportStore.reports())
                .filteredOn(report -> report.reviewId().equals(remove.reviewId()))
                .allMatch(report -> report.status() == ReviewReportStatus.RESOLVED);
        assertThat(moderationService.auditLog())
                .extracting(action -> List.of(action.actorType(), action.reason()))
                .containsExactly(
                        List.of(ModerationActorType.SYSTEM, ModerationReason.PII_HIGH_RISK),
                        List.of(ModerationActorType.OPERATOR, ModerationReason.FALSE_POSITIVE),
                        List.of(ModerationActorType.OPERATOR, ModerationReason.ABUSE_CONFIRMED),
                        List.of(ModerationActorType.OPERATOR, ModerationReason.FALSE_POSITIVE),
                        List.of(ModerationActorType.OPERATOR, ModerationReason.FALSE_POSITIVE),
                        List.of(ModerationActorType.OPERATOR, ModerationReason.FALSE_POSITIVE));

        assertTelemetryContainsNoSensitiveValues();
    }

    private org.springframework.test.web.servlet.ResultActions report(DrillCase drillCase, String key)
            throws Exception {
        return mockMvc.perform(post("/api/v1/visit-reviews/{reviewId}/reports", drillCase.reviewId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ReportBody(drillCase.reason(), drillCase.detail())))
                .cookie(
                        new jakarta.servlet.http.Cookie("__Host-onmaru-session", drillCase.session()),
                        new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", CSRF))
                .header("X-CSRF-TOKEN", CSRF)
                .header("Idempotency-Key", key)
                .header("X-Request-Id", "req-" + drillCase.session())
                .header("X-Run-Id", "run-" + drillCase.session())
                .header("X-Revision", "rev-" + CURRENT_TOKEN));
    }

    private org.springframework.test.web.servlet.ResultActions moderate(
            DrillCase drillCase,
            String nextStatus,
            String reason,
            String token) throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/operations/moderation/visit-reviews/{reviewId}", drillCase.reviewId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ModerationBody(nextStatus, reason)))
                .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", CSRF))
                .header("X-CSRF-TOKEN", CSRF)
                .header("Authorization", "Bearer " + token)
                .header("X-OnMaru-Operator", ACTOR_REF));
    }

    private void assertPublicReviewsExclude(String text) throws Exception {
        mockMvc.perform(get("/api/v1/visit-reviews").queryParam("scope", "ALL").queryParam("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items[?(@.text == '%s')]".formatted(text)).isEmpty());
        mockMvc.perform(get("/api/v1/places/p-jeonju-hanok-village/visit-reviews")
                        .queryParam("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items[?(@.text == '%s')]".formatted(text)).isEmpty());
    }

    private void assertPublicReviewsInclude(String text) throws Exception {
        mockMvc.perform(get("/api/v1/visit-reviews").queryParam("scope", "ALL").queryParam("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items[?(@.text == '%s')]".formatted(text)).isNotEmpty());
        mockMvc.perform(get("/api/v1/places/p-jeonju-hanok-village/visit-reviews")
                        .queryParam("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items[?(@.text == '%s')]".formatted(text)).isNotEmpty());
    }

    private void assertTelemetryContainsNoSensitiveValues() {
        Set<String> sensitiveValues = fixture.cases().stream()
                .flatMap(drillCase -> List.of(drillCase.session(), drillCase.detail(), drillCase.text()).stream())
                .collect(java.util.stream.Collectors.toSet());
        sensitiveValues.addAll(Set.of(CURRENT_TOKEN, PREVIOUS_TOKEN, ACTOR_REF));
        sensitiveValues.addAll(sensitiveMemberIds);

        List<String> telemetry = telemetrySink.events().stream()
                .map(TelemetryEvent::attributes)
                .flatMap(attributes -> attributes.entrySet().stream())
                .flatMap(entry -> List.of(entry.getKey(), entry.getValue()).stream())
                .toList();
        assertThat(telemetry).noneMatch(value -> sensitiveValues.stream().anyMatch(value::contains));
    }

    private VisitReviewProjection review(DrillCase drillCase, UUID authorId) {
        return new VisitReviewProjection(
                drillCase.reviewId(),
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                "kr-45-jeonju",
                35.8151,
                127.1530,
                drillCase.text(),
                Instant.parse("2026-09-15T02:00:00Z"),
                authorId,
                Set.of(),
                VisitReviewStatus.PUBLISHED);
    }

    private SessionRecord session(String token, UUID memberId) {
        return new SessionRecord(
                hasher.hash(token),
                memberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600));
    }

    private String idempotencyKey(int suffix) {
        return "00000000-0000-0000-0000-%012d".formatted(139_000 + suffix);
    }

    private record ReportBody(String reason, String detail) {
    }

    private record ModerationBody(String nextStatus, String reason) {
    }

    private record DrillCase(
            UUID reviewId,
            String session,
            String reason,
            String detail,
            String text,
            String action) {
    }

    private record DrillFixture(List<DrillCase> cases) {
        private DrillCase caseByAction(String action) {
            return cases.stream()
                    .filter(drillCase -> drillCase.action().equals(action))
                    .findFirst()
                    .orElseThrow();
        }
    }

    @TestConfiguration
    static class TelemetryTestConfig {

        @Bean
        @Primary
        InMemoryTelemetrySink inMemoryTelemetrySink() {
            return new InMemoryTelemetrySink();
        }
    }
}

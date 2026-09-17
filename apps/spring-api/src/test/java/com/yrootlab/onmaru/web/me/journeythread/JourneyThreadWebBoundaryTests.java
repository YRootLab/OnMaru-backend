package com.yrootlab.onmaru.web.me.journeythread;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunStatus;
import com.yrootlab.onmaru.journey.thread.JourneyThreadService;
import com.yrootlab.onmaru.journey.thread.JourneyThreadStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.UUID;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class JourneyThreadWebBoundaryTests {

    private static final Cookie CSRF_COOKIE = new Cookie("__Host-onmaru-csrf", "csrf-token");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private JourneyThreadService journeyThreadService;

    @Autowired
    private JourneyThreadStore journeyThreadStore;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    private UUID memberId;
    private UUID otherMemberId;

    @BeforeEach
    void setUp() {
        identityStore.clear();
        memberId = identityStore.createMember(clock.instant());
        otherMemberId = identityStore.createMember(clock.instant());
        identityStore.saveSession(new SessionRecord(
                hasher.hash("member-session"),
                memberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600)));
        identityStore.saveSession(new SessionRecord(
                hasher.hash("other-session"),
                otherMemberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600)));
    }

    @Test
    @DisplayName("인증되지 않은 접근은 401 UNAUTHORIZED 에러를 반환한다")
    void unauthenticatedAccessReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/me/journey-threads"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("회원의 탐색 기록 목록 조회, 상세 조회 및 삭제가 사용자 격리되어 동작한다")
    void listDetailAndDeleteAreOwnerScoped() throws Exception {
        var explorationId = UUID.randomUUID();
        var thread = journeyThreadService.recordOrSync(
                memberId,
                explorationId,
                "전주 한옥 탐색",
                "전주 한옥마을 조용한 곳 찾아줘 (010-1234-5678)",
                ExplorationRunOutcome.INITIAL_BOARD,
                ExplorationRunStatus.COMPLETED,
                null,
                1,
                3,
                UUID.randomUUID());

        // 1. List
        mockMvc.perform(get("/api/v1/me/journey-threads").cookie(session("member-session")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.items[0].threadId").value(thread.threadId().toString()))
                .andExpect(jsonPath("$.items[0].title").value("전주 한옥 탐색"))
                .andExpect(jsonPath("$.items[0].lastUserQueryPreview").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("010-1234-5678"))))
                .andExpect(jsonPath("$.items[0].lastUserQueryPreview").value(org.hamcrest.Matchers.containsString("[REDACTED_PHONE]")))
                .andExpect(jsonPath("$.hasMore").value(false));

        // 2. Detail
        mockMvc.perform(get("/api/v1/me/journey-threads/{id}", thread.threadId()).cookie(session("member-session")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.threadId").value(thread.threadId().toString()))
                .andExpect(jsonPath("$.turnHistory[0].redactedQuery").value(org.hamcrest.Matchers.containsString("[REDACTED_PHONE]")))
                .andExpect(jsonPath("$.snapshotUrl").value("/api/v1/explorations/" + explorationId));

        // 3. Other member cannot view
        mockMvc.perform(get("/api/v1/me/journey-threads/{id}", thread.threadId()).cookie(session("other-session")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 4. Delete
        mockMvc.perform(delete("/api/v1/me/journey-threads/{id}", thread.threadId())
                        .cookie(session("member-session"), CSRF_COOKIE)
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        // 5. List again - should be empty
        mockMvc.perform(get("/api/v1/me/journey-threads").cookie(session("member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    private Cookie session(String value) {
        return new Cookie("__Host-onmaru-session", value);
    }
}

package com.yrootlab.onmaru.web.stamp;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.stamp.InMemoryStampStore;
import com.yrootlab.onmaru.stamp.ranking.StampRankingEntry;
import com.yrootlab.onmaru.stamp.ranking.StampRankingIdentity;
import com.yrootlab.onmaru.stamp.ranking.StampRankingStatus;
import com.yrootlab.onmaru.stamp.ranking.StampRankingStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {OnMaruApplication.class, StampRankingWebBoundaryTests.TestRankingConfiguration.class},
        properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class StampRankingWebBoundaryTests {
    private static final String SESSION = "ranking-member-session";
    private static final String PRIVATE_PATH = "/api/v1/me/stamp-ranking";
    private static final String PUBLIC_PATH = "/api/v1/stamps/leaderboard";
    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    @Autowired private MockMvc mockMvc;
    @Autowired private InMemoryIdentityStore identities;
    @Autowired private InMemoryStampStore stamps;
    @Autowired private FailingRankingStore rankingStore;
    @Autowired private Clock clock;
    private UUID memberId;

    @BeforeEach
    void setUp() {
        identities.clear();
        stamps.clear();
        rankingStore.fail = false;
        memberId = identities.createMember(clock.instant());
        identities.saveSession(new SessionRecord(hasher.hash(SESSION), memberId,
                clock.instant(), clock.instant(), clock.instant().plusSeconds(3600)));
    }

    @Test
    void anonymousLeaderboardUsesDefaultLimitAndOnlyApprovedPublicFields() throws Exception {
        seedParticipants(25);
        var response = mockMvc.perform(get(PUBLIC_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.3"))
                .andExpect(jsonPath("$.generatedAt").isString())
                .andExpect(jsonPath("$.entries.length()").value(20))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[0].nicknameType").value("GENERATED"))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> entries = com.jayway.jsonpath.JsonPath.read(response, "$.entries");
        entries.forEach(entry -> assertThat(entry.keySet()).containsExactlyInAnyOrder(
                "rank", "publicId", "nickname", "nicknameType", "stampCount", "visitedRegionCount", "completionRate"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void acceptsInclusiveLimitBoundaries(int limit) throws Exception {
        seedParticipants(105);
        mockMvc.perform(get(PUBLIC_PATH).param("limit", Integer.toString(limit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(limit));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "invalid", "1.5", "2147483648"})
    void rejectsInvalidLimitsWithVersionedValidationEnvelope(String limit) throws Exception {
        assertError(mockMvc.perform(get(PUBLIC_PATH).param("limit", limit)), 400, "VALIDATION_ERROR");
    }

    @Test
    void personalReadAndMutationRequireAnActiveSession() throws Exception {
        assertError(mockMvc.perform(get(PRIVATE_PATH)), 401, "AUTH_REQUIRED");
        assertError(mockMvc.perform(csrf(put(PRIVATE_PATH))
                .contentType(MediaType.APPLICATION_JSON).content("{\"participating\":true}")), 401, "AUTH_REQUIRED");
        identities.requestDeletion(hasher.hash(SESSION), clock.instant());
        assertError(mockMvc.perform(auth(get(PRIVATE_PATH))), 401, "AUTH_REQUIRED");
        assertError(update("{\"participating\":true}"), 401, "AUTH_REQUIRED");
    }

    @Test
    void mutationRequiresMatchingCsrfAndSameOrigin() throws Exception {
        assertError(mockMvc.perform(auth(put(PRIVATE_PATH))
                .contentType(MediaType.APPLICATION_JSON).content("{\"participating\":true}")), 403, "CSRF_INVALID");
        assertError(mockMvc.perform(csrf(auth(put(PRIVATE_PATH))).header("Origin", "https://other.example")
                .contentType(MediaType.APPLICATION_JSON).content("{\"participating\":true}")), 403, "CSRF_INVALID");
    }

    @Test
    void nonParticipantHasExplicitNullPublicIdentityAndRank() throws Exception {
        mockMvc.perform(auth(get(PRIVATE_PATH)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.3"))
                .andExpect(jsonPath("$.participating").value(false))
                .andExpect(jsonPath("$.publicNickname").value(nullValue()))
                .andExpect(jsonPath("$.nicknameType").value(nullValue()))
                .andExpect(jsonPath("$.rank").value(nullValue()))
                .andExpect(jsonPath("$.participantCount").value(0))
                .andExpect(jsonPath("$.stampCount").value(0))
                .andExpect(jsonPath("$.visitedRegionCount").value(0))
                .andExpect(jsonPath("$.completionRate").value(0));
    }

    @Test
    void participationReplayPreservesIdentityAndWithdrawalIsImmediateAndIdempotent() throws Exception {
        var first = update("{\"participating\":true}")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.3"))
                .andExpect(jsonPath("$.participating").value(true))
                .andExpect(jsonPath("$.publicNickname").isString())
                .andExpect(jsonPath("$.nicknameType").value("GENERATED"))
                .andExpect(jsonPath("$.rank").value(1))
                .andExpect(jsonPath("$.participantCount").value(1))
                .andReturn().getResponse().getContentAsString();
        var publicEntry = stamps.leaderboard(1).getFirst();
        update("{\"participating\":true}").andExpect(status().isOk()).andExpect(content().json(first));
        mockMvc.perform(get(PUBLIC_PATH))
                .andExpect(jsonPath("$.entries[0].publicId").value(publicEntry.publicId().toString()))
                .andExpect(jsonPath("$.entries[0].nickname").value(publicEntry.publicNickname()));
        for (int replay = 0; replay < 2; replay++) {
            update("{\"participating\":false}")
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.participating").value(false))
                    .andExpect(jsonPath("$.publicNickname").value(nullValue()))
                    .andExpect(jsonPath("$.nicknameType").value(nullValue()))
                    .andExpect(jsonPath("$.rank").value(nullValue()));
            mockMvc.perform(get(PUBLIC_PATH)).andExpect(jsonPath("$.entries").isEmpty());
        }
    }

    @Test
    void immediateRejoinReturnsRateLimitAndRetryAfterInSeconds() throws Exception {
        update("{\"participating\":true}").andExpect(status().isOk());
        update("{\"participating\":false}").andExpect(status().isOk());
        var response = assertError(update("{\"participating\":true}"), 429, "RATE_LIMITED")
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.details.retryAfterSeconds").isNumber())
                .andReturn().getResponse();
        Number retry = com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.details.retryAfterSeconds");
        assertThat(retry.longValue()).isBetween(1L, 5L);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo(retry.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{\"participating\":null}", "{\"participating\":\"true\"}",
            "{\"participating\":1}", "{\"participating\":true,\"nickname\":\"custom\"}",
            "{\"participating\":true,\"memberId\":\"other\"}",
            "{\"participating\":true,\"unknownFields\":{}}", "{\"participating\":true,\"unknownFields\":null}",
            "{\"participating\":[]}", "{\"participating\":{}}", "true", "[]", "{broken"})
    void acceptsOnlyParticipatingBoolean(String body) throws Exception {
        assertError(update(body), 400, "VALIDATION_ERROR");
        assertThat(stamps.status(memberId).participating()).isFalse();
    }

    @Test
    void unavailableStoreReturnsSanitizedVersionedError() throws Exception {
        rankingStore.fail = true;
        assertError(mockMvc.perform(get(PUBLIC_PATH)), 503, "SERVICE_UNAVAILABLE");
        assertError(mockMvc.perform(auth(get(PRIVATE_PATH))), 503, "SERVICE_UNAVAILABLE");
        assertError(update("{\"participating\":true}"), 503, "SERVICE_UNAVAILABLE");
    }

    @Test
    void unrelatedCsrfErrorsRetainVersion12() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"));
    }

    private void seedParticipants(int count) {
        for (int index = 0; index < count; index++) {
            stamps.participate(UUID.randomUUID(), StampRankingIdentity.generated(UUID.randomUUID(), "여행자-" + index),
                    clock.instant());
        }
    }

    private ResultActions update(String body) throws Exception {
        return mockMvc.perform(csrf(auth(put(PRIVATE_PATH))).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("__Host-onmaru-session", SESSION));
    }

    private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("__Host-onmaru-csrf", "csrf-token")).header("X-CSRF-TOKEN", "csrf-token");
    }

    private ResultActions assertError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.3"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(content().string(not(containsString("database-secret-must-not-leak"))))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @TestConfiguration
    static class TestRankingConfiguration {
        @Bean @Primary
        FailingRankingStore testRankingStore(InMemoryStampStore store) {
            return new FailingRankingStore(store);
        }
    }

    static final class FailingRankingStore implements StampRankingStore {
        private final InMemoryStampStore delegate;
        private boolean fail;
        FailingRankingStore(InMemoryStampStore delegate) { this.delegate = delegate; }
        private void checkAvailability() {
            if (fail) throw new IllegalStateException("database-secret-must-not-leak");
        }
        public List<StampRankingEntry> leaderboard(int limit) {
            checkAvailability(); return delegate.leaderboard(limit);
        }
        public StampRankingStatus status(UUID memberId) {
            checkAvailability(); return delegate.status(memberId);
        }
        public StampRankingStatus participate(UUID memberId, StampRankingIdentity identity, Instant now) {
            checkAvailability(); return delegate.participate(memberId, identity, now);
        }
        public StampRankingStatus withdraw(UUID memberId, Instant now) {
            checkAvailability(); return delegate.withdraw(memberId, now);
        }
    }
}

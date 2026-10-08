package com.yrootlab.onmaru.web.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.operations.admission.AdmissionPolicy;
import com.yrootlab.onmaru.operations.admission.AdmissionService;
import com.yrootlab.onmaru.operations.admission.InMemoryAdmissionStore;
import com.yrootlab.onmaru.operations.admission.OperationBudget;
import com.yrootlab.onmaru.operations.admission.SubjectType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryReadAdmissionFilterTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AdmissionPolicy POLICY = new AdmissionPolicy(Duration.ofMinutes(1), List.of(
            new OperationBudget("discovery.read", SubjectType.IP, 1)));

    @Test void allSixPublicReadRoutesShareOneBudgetAndReturnContractError() throws Exception {
        var paths = List.of(
                "/api/v1/discovery/topics",
                "/api/v1/discovery/places",
                "/api/v1/discovery/places/p-example",
                "/api/v1/k-contents/works",
                "/api/v1/k-contents/works/00000000-0000-4000-8000-000000000001",
                "/api/v1/places/p-example/k-contents");
        for (var path : paths) {
            var filter = filter(true, new InMemoryAdmissionStore(), List.of());
            assertThat(get(filter, path, "203.0.113.1", null).getStatus()).isEqualTo(200);
            var denied = get(filter, path, "203.0.113.1", null);
            assertThat(denied.getStatus()).isEqualTo(429);
            assertThat(denied.getHeader("Retry-After")).isEqualTo("55");
            var error = JSON.readTree(denied.getContentAsString());
            assertThat(error.path("code").asText()).isEqualTo("RATE_LIMITED");
            assertThat(error.path("details").path("retryAfterMs").asLong()).isEqualTo(55000);
        }
        var shared = filter(true, new InMemoryAdmissionStore(), List.of());
        assertThat(get(shared, paths.get(0), "203.0.113.2", null).getStatus()).isEqualTo(200);
        assertThat(get(shared, paths.get(3), "203.0.113.2", null).getStatus()).isEqualTo(429);
    }

    @Test void existingRoutesAndDisabledDiscoveryDoNotConsumeTheNewBudget() throws Exception {
        var enabled = filter(true, new InMemoryAdmissionStore(), List.of());
        var existing = List.of("/api/v1/home/popular-regions", "/api/v1/hanoks",
                "/api/v1/map/places", "/api/v1/places/p-old", "/api/v1/saved-resources",
                "/api/v1/odii/stories", "/api/v1/hanoks/screen-hanok");
        for (int i = 0; i < 3; i++) {
            for (var path : existing) {
                assertThat(get(enabled, path, "203.0.113.3", null).getStatus()).isEqualTo(200);
            }
        }
        assertThat(get(enabled, "/api/v1/discovery/topics", "203.0.113.3", null).getStatus()).isEqualTo(200);
        var disabled = filter(false, new InMemoryAdmissionStore(), List.of());
        assertThat(get(disabled, "/api/v1/discovery/topics", "203.0.113.3", null).getStatus()).isEqualTo(200);
        assertThat(get(disabled, "/api/v1/discovery/topics", "203.0.113.3", null).getStatus()).isEqualTo(200);
    }

    @Test void untrustedForwardedAddressCannotBypassLimitButTrustedProxySeparatesClients() throws Exception {
        var untrusted = filter(true, new InMemoryAdmissionStore(), List.of());
        assertThat(get(untrusted, "/api/v1/discovery/topics", "203.0.113.4", "198.51.100.1").getStatus()).isEqualTo(200);
        assertThat(get(untrusted, "/api/v1/discovery/topics", "203.0.113.4", "198.51.100.2").getStatus()).isEqualTo(429);

        var trusted = filter(true, new InMemoryAdmissionStore(), List.of("10.0.0.10"));
        assertThat(get(trusted, "/api/v1/discovery/topics", "10.0.0.10", "198.51.100.1").getStatus()).isEqualTo(200);
        assertThat(get(trusted, "/api/v1/discovery/topics", "10.0.0.10", "198.51.100.2").getStatus()).isEqualTo(200);
        assertThat(get(trusted, "/api/v1/discovery/topics", "10.0.0.10", "198.51.100.1").getStatus()).isEqualTo(429);
    }

    @Test void nextMinuteAllowsTheSameClientAgain() throws Exception {
        var store = new InMemoryAdmissionStore();
        var first = filter(true, store, List.of());
        assertThat(get(first, "/api/v1/discovery/topics", "203.0.113.5", null).getStatus()).isEqualTo(200);
        assertThat(get(first, "/api/v1/discovery/topics", "203.0.113.5", null).getStatus()).isEqualTo(429);
        var later = new AdmissionFilter(new AdmissionService(store,
                Clock.fixed(Instant.parse("2026-09-15T03:01:05Z"), ZoneOffset.UTC)), POLICY,
                new ClientIdentityResolver(new TrustedProxyProperties(List.of())), true);
        assertThat(get(later, "/api/v1/discovery/topics", "203.0.113.5", null).getStatus()).isEqualTo(200);
    }

    private static AdmissionFilter filter(boolean enabled, InMemoryAdmissionStore store, List<String> trusted) {
        return new AdmissionFilter(new AdmissionService(store,
                Clock.fixed(Instant.parse("2026-09-15T03:00:05Z"), ZoneOffset.UTC)), POLICY,
                new ClientIdentityResolver(new TrustedProxyProperties(trusted)), enabled);
    }

    private static MockHttpServletResponse get(AdmissionFilter filter, String path, String remote, String forwarded)
            throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(remote);
        if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}

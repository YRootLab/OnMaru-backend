package com.yrootlab.onmaru.security.web;

import com.yrootlab.onmaru.OnMaruApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {OnMaruApplication.class, SecurityWebBoundaryTests.SecurityBoundaryTestConfig.class},
        properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class SecurityWebBoundaryTests {

    private static final Pattern CSRF_COOKIE =
            Pattern.compile("(__Host-onmaru-csrf)=([^;]+);.*Path=/.*Secure.*HttpOnly.*SameSite=Lax");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void csrfTokenEndpointReturnsHeaderNameAndSecureHttpOnlyCookie() throws Exception {
        var result = mockMvc.perform(get("/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.token", not(emptyOrNullString())))
                .andReturn();

        var setCookie = result.getResponse().getHeader("Set-Cookie");

        assertThat(setCookie).isNotBlank();
        assertThat(CSRF_COOKIE.matcher(setCookie).matches()).isTrue();
    }

    @Test
    void unsafeApiRequestsWithoutMatchingCsrfTokenReturn403Envelope() throws Exception {
        mockMvc.perform(post("/api/security/protected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("X-Request-Id", "req-csrf-missing"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
                .andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.not("req-csrf-missing")));

        mockMvc.perform(post("/api/security/protected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "cookie-token"))
                        .header("X-CSRF-TOKEN", "header-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void unsafeApiRequestsRejectCrossSiteOriginEvenWithValidCsrfToken() throws Exception {
        mockMvc.perform(post("/api/security/protected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .secure(true)
                        .header("Host", "onmaru.test")
                        .header("Origin", "https://evil.example")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "same-token"))
                        .header("X-CSRF-TOKEN", "same-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void unsafeApiRequestsWithMatchingCsrfTokenAreAllowed() throws Exception {
        mockMvc.perform(post("/api/security/protected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "same-token"))
                        .header("X-CSRF-TOKEN", "same-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    void privateResponsesAreNoStoreAndHaveBrowserSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/security/private"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy", "geolocation=()"));
    }

    @Test
    void ownershipMismatchReturns404WithoutRevealingResource() throws Exception {
        var ownerId = UUID.randomUUID();
        var otherActorId = UUID.randomUUID();

        mockMvc.perform(delete("/api/security/resources/{ownerId}", ownerId)
                        .header("X-Actor-Id", otherActorId)
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "same-token"))
                        .header("X-CSRF-TOKEN", "same-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @TestConfiguration
    static class SecurityBoundaryTestConfig {

        @Bean
        SecurityBoundaryProbeController securityBoundaryProbeController() {
            return new SecurityBoundaryProbeController();
        }
    }

    @RestController
    static class SecurityBoundaryProbeController {

        @PostMapping("/api/security/protected")
        org.springframework.http.ResponseEntity<Void> protectedCommand() {
            return org.springframework.http.ResponseEntity.noContent().build();
        }

        @PrivateResponse
        @GetMapping("/api/security/private")
        java.util.Map<String, String> privateResponse() {
            return java.util.Map.of("scope", "private");
        }

        @DeleteMapping("/api/security/resources/{ownerId}")
        org.springframework.http.ResponseEntity<Void> deleteOwnedResource(
                @PathVariable UUID ownerId,
                @org.springframework.web.bind.annotation.RequestHeader("X-Actor-Id") UUID actorId) {
            OwnershipGuard.requireOwner(actorId, ownerId);
            return org.springframework.http.ResponseEntity.noContent().build();
        }
    }
}

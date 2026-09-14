package com.yrootlab.onmaru.web.admission;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.operations.admission.AdmissionPolicy;
import com.yrootlab.onmaru.operations.admission.OperationBudget;
import com.yrootlab.onmaru.operations.admission.SubjectType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {OnMaruApplication.class, AdmissionWebBoundaryTests.AdmissionWebTestConfig.class},
        properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class AdmissionWebBoundaryTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void untrustedForwardedForIsIgnoredAndRetryAfterEnvelopeUsesRemoteAddressBudget() throws Exception {
        mockMvc.perform(post("/api/admission/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "same-token"))
                        .header("X-CSRF-TOKEN", "same-token")
                        .header("X-Forwarded-For", "198.51.100.1")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            return request;
                        }))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/admission/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "same-token"))
                        .header("X-CSRF-TOKEN", "same-token")
                        .header("X-Request-Id", "req-rate-limited")
                        .header("X-Forwarded-For", "198.51.100.2")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            return request;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "55"))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.requestId").value("req-rate-limited"))
                .andExpect(jsonPath("$.details.retryAfterMs").value(55000));
    }

    @Test
    void trustedProxyUsesFirstForwardedForClientIdentity() throws Exception {
        mockMvc.perform(post("/api/admission/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "same-token"))
                        .header("X-CSRF-TOKEN", "same-token")
                        .header("X-Forwarded-For", "198.51.100.11, 10.0.0.1")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.10");
                            return request;
                        }))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/admission/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "same-token"))
                        .header("X-CSRF-TOKEN", "same-token")
                        .header("X-Forwarded-For", "198.51.100.12, 10.0.0.1")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.10");
                            return request;
                        }))
                .andExpect(status().isNoContent());
    }

    @TestConfiguration
    static class AdmissionWebTestConfig {

        @Bean
        @Primary
        AdmissionPolicy testAdmissionPolicy() {
            return new AdmissionPolicy(Duration.ofMinutes(1), List.of(
                    new OperationBudget("login.start", SubjectType.IP, 1)
            ));
        }

        @Bean
        @Primary
        Clock admissionClock() {
            return Clock.fixed(Instant.parse("2026-09-15T03:00:05Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        TrustedProxyProperties testTrustedProxyProperties() {
            return new TrustedProxyProperties(List.of("10.0.0.10"));
        }

        @Bean
        AdmissionProbeController admissionProbeController() {
            return new AdmissionProbeController();
        }
    }

    @RestController
    static class AdmissionProbeController {

        @PostMapping("/api/admission/login")
        org.springframework.http.ResponseEntity<Void> login() {
            return org.springframework.http.ResponseEntity.noContent().build();
        }
    }
}

package com.yrootlab.onmaru.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class CorrelationFilterTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryTelemetrySink telemetrySink;

    @BeforeEach
    void clearTelemetry() {
        telemetrySink.clear();
    }

    @Test
    void correlatesRequestRunRevisionAndTraceWithoutSensitiveAttributes() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .header("X-Request-Id", "req-123")
                        .header("X-Run-Id", "run-456")
                        .header("X-Revision", "rev-789")
                        .header("traceparent",
                                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                        .header("Cookie", "session=secret")
                        .header("Authorization", "Bearer secret-token")
                        .queryParam("query", "secret")
                        .queryParam("location", "secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req-123"))
                .andExpect(header().string("X-Run-Id", "run-456"))
                .andExpect(header().string("X-Revision", "rev-789"));

        TelemetryEvent event = telemetrySink.events().getFirst();
        assertThat(event.name()).isEqualTo("http.server.request");
        assertThat(event.attributes())
                .containsEntry("request.id", "req-123")
                .containsEntry("trace.id", "4bf92f3577b34da6a3ce929d0e0e4736")
                .containsEntry("run.id", "run-456")
                .containsEntry("revision", "rev-789")
                .containsEntry("http.request.method", "GET")
                .containsEntry("http.response.status_code", "200");
        assertThat(event.attributes().keySet())
                .doesNotContain("query", "location", "cookie", "token", "evidence.body");
        assertThat(event.attributes().values())
                .doesNotContain("secret", "secret-token", "session=secret");
    }

    @Test
    void reusesGeneratedRequestIdForResponseAndTelemetry() throws Exception {
        var result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn();

        var requestId = result.getResponse().getHeader("X-Request-Id");
        assertThat(requestId).isNotBlank();
        assertThat(telemetrySink.events().getFirst().attributes())
                .containsEntry("request.id", requestId);
    }

    @Test
    void replacesSensitiveValuesInjectedThroughCorrelationHeaders() throws Exception {
        var reporterId = "8ce13b1d-01bb-42ea-8457-5e0df299a5de";
        var reportDetail = "연락처로 의심되는 문자열";
        var operatorToken = "fake-moderation-operator-token-current";

        var result = mockMvc.perform(get("/actuator/health")
                        .header("X-Request-Id", reporterId)
                        .header("X-Run-Id", reportDetail)
                        .header("X-Revision", operatorToken))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Request-Id")).isNotEqualTo(reporterId);
        assertThat(result.getResponse().getHeader("X-Run-Id")).isEqualTo("unknown");
        assertThat(result.getResponse().getHeader("X-Revision")).isEqualTo("unknown");
        assertThat(telemetrySink.events().getFirst().attributes().values())
                .doesNotContain(reporterId, reportDetail, operatorToken);
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

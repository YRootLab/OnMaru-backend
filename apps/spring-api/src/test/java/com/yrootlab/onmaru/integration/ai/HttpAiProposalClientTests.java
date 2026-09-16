package com.yrootlab.onmaru.integration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yrootlab.onmaru.config.secrets.FakeSecretProvider;
import com.yrootlab.onmaru.integration.ai.security.InternalAiRequestHeadersFactory;
import com.yrootlab.onmaru.integration.ai.security.InternalAiTokenProperties;
import com.yrootlab.onmaru.integration.ai.security.InternalAiTokenSigner;
import com.yrootlab.onmaru.journey.worker.AiProposalException;
import com.yrootlab.onmaru.journey.worker.CandidatePayload;
import com.yrootlab.onmaru.journey.worker.JourneyCandidate;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRequest;
import com.yrootlab.onmaru.journey.worker.WorkerDegradedReason;
import com.yrootlab.onmaru.journey.worker.WorkerTelemetryEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpAiProposalClientTests {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-16T00:00:00Z"),
            ZoneOffset.UTC);
    private HttpServer server;
    private Map<String, String> capturedHeaders;
    private String capturedBody;
    private final List<WorkerTelemetryEvent> events = new ArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsBoundedInternalRequestWithAuthCorrelationRevisionAndCandidateCount() throws Exception {
        server = server((exchange) -> {
            capturedHeaders = Map.of(
                    "Authorization", exchange.getRequestHeaders().getFirst("Authorization"),
                    "X-Request-Id", exchange.getRequestHeaders().getFirst("X-Request-Id"),
                    "X-Run-Id", exchange.getRequestHeaders().getFirst("X-Run-Id"),
                    "X-Revision", exchange.getRequestHeaders().getFirst("X-Revision"),
                    "traceparent", exchange.getRequestHeaders().getFirst("traceparent"));
            capturedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, """
                    {"schemaVersion":"internal.ai.v1","runId":"%s","orderedRefs":["place:002","place:001"],"outcome":"PROPOSAL"}
                    """.formatted(request().runId()));
        });
        var client = client(Duration.ofSeconds(1));

        var plan = client.propose(request(), candidates());

        assertThat(plan.outcome()).isEqualTo("PROPOSAL");
        assertThat(plan.orderedRefs()).containsExactly("place:002", "place:001");
        assertThat(capturedHeaders.get("Authorization")).startsWith("Bearer ");
        assertThat(capturedHeaders)
                .containsEntry("X-Request-Id", "req-ai-001")
                .containsEntry("X-Run-Id", request().runId().toString())
                .containsEntry("X-Revision", "dataset-2026-09-16")
                .containsEntry("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01");
        assertThat(capturedBody).contains("\"candidateCount\":2");
        assertThat(capturedBody).doesNotContain("조용한 한옥 코스");
        assertThat(events).contains(new WorkerTelemetryEvent(
                "journey.worker.ai_call",
                Map.of(
                        "run.id", request().runId().toString(),
                        "ai.call.status", "SUCCESS",
                        "http.status", "200")));
    }

    @Test
    void mapsRateLimitToQuotaExceededAndRecordsTelemetry() throws Exception {
        server = server(exchange -> respond(exchange, 429, "{\"code\":\"quota\"}"));
        var client = client(Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.propose(request(), candidates()))
                .isInstanceOf(AiProposalException.class)
                .extracting("degradedReason")
                .isEqualTo(WorkerDegradedReason.AI_QUOTA_EXCEEDED);
        assertThat(events).contains(new WorkerTelemetryEvent(
                "journey.worker.ai_call",
                Map.of(
                        "run.id", request().runId().toString(),
                        "ai.call.status", "FAILURE",
                        "degraded.reason", "AI_QUOTA_EXCEEDED",
                        "http.status", "429")));
    }

    @Test
    void mapsInvalidResponseToInvalidResponse() throws Exception {
        server = server(exchange -> respond(exchange, 200, "{\"orderedRefs\":[\"place:001\"]}"));
        var client = client(Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.propose(request(), candidates()))
                .isInstanceOf(AiProposalException.class)
                .extracting("degradedReason")
                .isEqualTo(WorkerDegradedReason.AI_INVALID_RESPONSE);
    }

    @Test
    void mapsSlowResponseToTimeout() throws Exception {
        server = server(exchange -> {
            try {
                Thread.sleep(300);
                respond(exchange, 200, "{}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        var client = client(Duration.ofMillis(50));

        assertThatThrownBy(() -> client.propose(request(), candidates()))
                .isInstanceOf(AiProposalException.class)
                .extracting("degradedReason")
                .isEqualTo(WorkerDegradedReason.AI_TIMEOUT);
    }

    private HttpAiProposalClient client(Duration timeout) {
        var headers = new InternalAiRequestHeadersFactory(new InternalAiTokenSigner(
                new FakeSecretProvider(),
                InternalAiTokenProperties.defaults(),
                FIXED_CLOCK));
        return new HttpAiProposalClient(
                HttpClient.newBuilder().connectTimeout(timeout).build(),
                new ObjectMapper(),
                URI.create("http://localhost:%d".formatted(server.getAddress().getPort())),
                headers,
                timeout,
                events::add);
    }

    private static JourneyWorkerRequest request() {
        return new JourneyWorkerRequest(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "MEMBER:123",
                1,
                "dataset-2026-09-16",
                "seoul-jongno",
                "조용한 한옥 코스",
                1,
                "req-ai-001",
                "4bf92f3577b34da6a3ce929d0e0e4736",
                Instant.parse("2026-09-16T00:00:20Z"));
    }

    private static CandidatePayload candidates() {
        return new CandidatePayload(
                "dataset-2026-09-16",
                List.of(new JourneyCandidate("place:001"), new JourneyCandidate("place:002")));
    }

    private static HttpServer server(Handler handler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/v1/journey/proposals", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}

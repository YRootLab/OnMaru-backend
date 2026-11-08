package com.yrootlab.onmaru.integration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.yrootlab.onmaru.config.secrets.FakeSecretProvider;
import com.yrootlab.onmaru.integration.ai.plan.AiPlanWireCodec;
import com.yrootlab.onmaru.journey.worker.AiProposalException;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;
import org.junit.jupiter.api.*;

import java.net.*;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

class HttpAiPlanExecuteClientTests {
    private final ObjectMapper json = new ObjectMapper();
    private final AiPlanWireCodec codec = new AiPlanWireCodec();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<com.sun.net.httpserver.Headers> headers = new CopyOnWriteArrayList<>();
    private final Instant now = Instant.parse("2026-11-08T00:00:00Z");
    private HttpServer server;
    private SafeQueryRequest safe;
    private PlanProposal proposal;
    private byte[] planBody;
    private int responseStatus = 200;
    private byte[] executeBody;

    @BeforeEach void start() throws Exception {
        try (var stream = getClass().getResourceAsStream("/ai-plan-execute/accepted-plan-sql_only.json")) {
            var fixture = json.readTree(stream);
            planBody = json.writeValueAsBytes(fixture.get("payload"));
            proposal = codec.readPlan(planBody);
            safe = codec.readSafeQuery(json.writeValueAsBytes(fixture.path("context").get("request")));
        }
        executeBody = codec.write(new ExecutionResult("ai-execute-result.v1", safe.runId(), proposal.planHash(), safe.versions(), null, null));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath()); headers.add(exchange.getRequestHeaders());
            exchange.getRequestBody().readAllBytes();
            byte[] body = exchange.getRequestURI().getPath().endsWith("plan") ? planBody : executeBody;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void usesOperationScopesValidTraceDeadlineAndProgressTokenWithoutLegacyHop() throws Exception {
        var client = client(Duration.ofSeconds(1));
        var context = new CallContext("req-808", safe.runId(), "1".repeat(32), now.plusSeconds(20));
        var plan = client.plan(safe, context);
        var approved = new ApprovedPlanBinding(plan).forExecution("grant-808");
        var result = client.execute(approved, context, "progress-capability");
        assertThat(result.planHash()).isEqualTo(proposal.planHash());
        assertThat(paths).containsExactly("/internal/v1/journey/plan", "/internal/v1/journey/execute");
        var traces = new ArrayList<String>();
        for (int i=0; i<headers.size(); i++) {
            var h = headers.get(i); var claims = claims(h.getFirst("Authorization"));
            assertThat(claims.path("scope").asText()).isEqualTo(i==0 ? "journey.plan:write" : "journey.execute:write");
            assertThat(claims.path("exp").asLong()).isEqualTo(now.plusSeconds(20).getEpochSecond());
            assertThat(h.getFirst("X-Deadline-At")).isEqualTo("2026-11-08T00:00:20Z");
            assertThat(h.getFirst("X-Run-Id")).isEqualTo(safe.runId().toString());
            assertThat(h.getFirst("traceparent")).matches("00-" + "1".repeat(32) + "-(?!0000000000000000)[0-9a-f]{16}-01");
            traces.add(h.getFirst("traceparent"));
        }
        assertThat(traces.get(0)).isNotEqualTo(traces.get(1));
        assertThat(headers.get(0).getFirst("X-Run-Progress-Token")).isNull();
        assertThat(headers.get(1).getFirst("X-Run-Progress-Token")).isEqualTo("progress-capability");
    }

    @Test void rejectsExpiredDeadlineAndInvalidTraceBeforeNetwork() {
        var client = client(Duration.ofSeconds(1));
        assertThatThrownBy(() -> client.plan(safe, new CallContext("req",safe.runId(),"1".repeat(32),now))).isInstanceOf(AiProposalException.class);
        assertThatThrownBy(() -> client.plan(safe, new CallContext("req",safe.runId(),"0".repeat(32),now.plusSeconds(10)))).isInstanceOf(AiProposalException.class);
        assertThat(paths).isEmpty();
    }

    @Test void rejectsUnboundOrMalformedResultsAndNeverRetriesConflict() throws Exception {
        var client = client(Duration.ofSeconds(1));
        var context = new CallContext("req-808",safe.runId(),"1".repeat(32),now.plusSeconds(20));
        var approved = new ApprovedPlanBinding(proposal).forExecution("grant-808");
        ObjectNode result = (ObjectNode)json.readTree(executeBody);
        result.put("planHash", "sha256:"+"b".repeat(64)); executeBody=json.writeValueAsBytes(result);
        assertThatThrownBy(() -> client.execute(approved,context,null)).isInstanceOf(AiProposalException.class);
        executeBody="{\"broken\":true}".getBytes();
        assertThatThrownBy(() -> client.execute(approved,context,null)).isInstanceOf(AiProposalException.class);
        responseStatus=409;
        assertThatThrownBy(() -> client.execute(approved,context,null)).isInstanceOf(AiProposalException.class);
        assertThat(paths).hasSize(3);
    }

    private HttpAiPlanExecuteClient client(Duration timeout) {
        return new HttpAiPlanExecuteClient(HttpClient.newHttpClient(), codec,
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()), new FakeSecretProvider(), timeout, Clock.fixed(now,ZoneOffset.UTC));
    }
    private com.fasterxml.jackson.databind.JsonNode claims(String bearer) throws Exception {
        return json.readTree(Base64.getUrlDecoder().decode(bearer.substring(7).split("\\.")[1]));
    }
}

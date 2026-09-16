package com.yrootlab.onmaru.worker.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yrootlab.onmaru.config.secrets.FakeSecretProvider;
import com.yrootlab.onmaru.integration.ai.HttpAiProposalClient;
import com.yrootlab.onmaru.integration.ai.security.InternalAiRequestHeadersFactory;
import com.yrootlab.onmaru.integration.ai.security.InternalAiTokenProperties;
import com.yrootlab.onmaru.integration.ai.security.InternalAiTokenSigner;
import com.yrootlab.onmaru.journey.run.AdvanceRunStageCommand;
import com.yrootlab.onmaru.journey.run.ClaimRunCommand;
import com.yrootlab.onmaru.journey.run.CreateRunCommand;
import com.yrootlab.onmaru.journey.run.FinishRunCommand;
import com.yrootlab.onmaru.journey.run.JourneyRunSnapshot;
import com.yrootlab.onmaru.journey.run.JourneyRunStage;
import com.yrootlab.onmaru.journey.run.JourneyRunStatus;
import com.yrootlab.onmaru.journey.run.JourneyRunStore;
import com.yrootlab.onmaru.journey.run.RunCommandReceipt;
import com.yrootlab.onmaru.journey.run.RunCommandResult;
import com.yrootlab.onmaru.journey.worker.DefaultBaselinePlanner;
import com.yrootlab.onmaru.journey.worker.InMemoryJourneyCandidateProvider;
import com.yrootlab.onmaru.journey.worker.InMemoryJourneyWorkerQueue;
import com.yrootlab.onmaru.journey.worker.JourneyCandidate;
import com.yrootlab.onmaru.journey.worker.JourneyResultEngine;
import com.yrootlab.onmaru.journey.worker.JourneyResultStore;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerOutcome;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRequest;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRunner;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerService;
import com.yrootlab.onmaru.journey.worker.PersistJourneyResultCommand;
import com.yrootlab.onmaru.journey.worker.PersistJourneyResultResult;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyWorkerEndToEndTests {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-16T00:00:00Z"),
            ZoneOffset.UTC);
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void drainsQueueCallsFastApiPersistsProposalFinishesRunAndEmitsTelemetry() throws Exception {
        server = server(exchange -> respond(exchange, """
                {"schemaVersion":"internal.ai.v1","runId":"%s","orderedRefs":["place:002","place:001"],"outcome":"PROPOSAL"}
                """.formatted(request().runId())));
        var runStore = new RecordingRunStore();
        var resultStore = new RecordingResultStore(PersistJourneyResultResult.PERSISTED);
        var telemetry = new ArrayList<WorkerTelemetryEvent>();
        var queue = new InMemoryJourneyWorkerQueue();
        var provider = new InMemoryJourneyCandidateProvider();
        provider.put("dataset-2026-09-16", "seoul-jongno", List.of(
                new JourneyCandidate("place:001"),
                new JourneyCandidate("place:002")));
        var service = new JourneyWorkerService(
                runStore,
                provider,
                aiClient(telemetry),
                new DefaultBaselinePlanner(),
                resultStore,
                telemetry::add);
        var runner = new JourneyWorkerRunner(queue, service::process);
        queue.enqueue(request());

        var drain = runner.drain();

        assertThat(drain.outcomes()).containsExactly(JourneyWorkerOutcome.COMPLETED_LLM);
        assertThat(resultStore.commands).containsExactly(new PersistJourneyResultCommand(
                request().runId(),
                request().explorationId(),
                request().baseVersion(),
                JourneyResultEngine.LLM,
                null,
                List.of("place:002", "place:001"),
                "PROPOSAL"));
        assertThat(runStore.finished).hasSize(1);
        assertThat(telemetry).contains(
                new WorkerTelemetryEvent("journey.worker.started", Map.of(
                        "run.id", request().runId().toString())),
                new WorkerTelemetryEvent("journey.worker.candidates_retrieved", Map.of(
                        "run.id", request().runId().toString(),
                        "candidate.count", "2")),
                new WorkerTelemetryEvent("journey.worker.ai_call", Map.of(
                        "run.id", request().runId().toString(),
                        "ai.call.status", "SUCCESS",
                        "http.status", "200")),
                new WorkerTelemetryEvent("journey.worker.completed", Map.of(
                        "run.id", request().runId().toString(),
                        "engine", "LLM")));
    }

    private HttpAiProposalClient aiClient(List<WorkerTelemetryEvent> telemetry) {
        var headers = new InternalAiRequestHeadersFactory(new InternalAiTokenSigner(
                new FakeSecretProvider(),
                InternalAiTokenProperties.defaults(),
                FIXED_CLOCK));
        return new HttpAiProposalClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                new ObjectMapper(),
                URI.create("http://localhost:%d".formatted(server.getAddress().getPort())),
                headers,
                Duration.ofSeconds(1),
                telemetry::add);
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
                0,
                "req-ai-001",
                "4bf92f3577b34da6a3ce929d0e0e4736",
                Instant.parse("2026-09-16T00:00:20Z"));
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

    private static void respond(HttpExchange exchange, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final class RecordingRunStore implements JourneyRunStore {
        final List<FinishRunCommand> finished = new ArrayList<>();

        @Override
        public RunCommandResult create(CreateRunCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunCommandResult claim(ClaimRunCommand command) {
            return result(JourneyRunStatus.RUNNING, null, 2);
        }

        @Override
        public RunCommandResult advance(AdvanceRunStageCommand command) {
            return result(JourneyRunStatus.RUNNING, command.nextStage(), command.expectedGeneration() + 1);
        }

        @Override
        public RunCommandResult finish(FinishRunCommand command) {
            finished.add(command);
            return result(command.terminalStatus(), null, command.expectedGeneration() + 1);
        }

        @Override
        public Optional<JourneyRunSnapshot> find(UUID runId, String actorKey) {
            return Optional.empty();
        }

        private RunCommandResult result(JourneyRunStatus status, JourneyRunStage stage, int generation) {
            return new RunCommandResult(
                    new RunCommandReceipt(request().runId(), status, stage, null, generation),
                    false);
        }
    }

    private static final class RecordingResultStore implements JourneyResultStore {
        final List<PersistJourneyResultCommand> commands = new ArrayList<>();
        private final PersistJourneyResultResult result;

        private RecordingResultStore(PersistJourneyResultResult result) {
            this.result = result;
        }

        @Override
        public PersistJourneyResultResult persist(PersistJourneyResultCommand command) {
            commands.add(command);
            return result;
        }
    }
}

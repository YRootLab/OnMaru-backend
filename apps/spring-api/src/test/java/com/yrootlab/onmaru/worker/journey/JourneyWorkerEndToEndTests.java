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
import com.yrootlab.onmaru.journey.worker.AiProposalClient;
import com.yrootlab.onmaru.journey.worker.CandidatePayload;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerPlan;
import com.yrootlab.onmaru.journey.events.JourneyRunEventBuffer;
import com.yrootlab.onmaru.journey.events.JourneyRunEventSink;
import com.yrootlab.onmaru.journey.events.JourneyRunEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import java.util.function.Consumer;

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
    void internalDeltasReachPublicSinkBeforeTerminalAndValidatedSnapshotPersistence() throws Exception {
        server = server(exchange -> respondStream(exchange, "event: text.delta\ndata: {\"text\":\"한옥 산책을 구성해요.\"}\n\n"
                + "event: text.delta\ndata: {\"text\":\"골목을 이어봐요.\"}\n\n" + finalProposal()));
        var runStore = new RecordingRunStore();
        var resultStore = new RecordingResultStore(PersistJourneyResultResult.PERSISTED);
        var telemetry = new ArrayList<WorkerTelemetryEvent>();
        var sink = new JourneyRunEventBuffer(64, Duration.ofSeconds(15));
        var outcome = streamingWorker(runStore, resultStore, aiClient(telemetry), sink, telemetry).process(request());
        assertThat(outcome).isEqualTo(JourneyWorkerOutcome.COMPLETED_LLM);
        var events = sink.replay(request().runId(), null).events();
        assertThat(events.stream().filter(event -> event.type() == JourneyRunEventType.TEXT_DELTA).map(event -> event.data()))
                .anyMatch(data -> data.contains("한옥 산책을 구성해요"))
                .anyMatch(data -> data.contains("골목을 이어봐요"));
        assertThat(events.getLast().type()).isEqualTo(JourneyRunEventType.TERMINAL);
        assertThat(resultStore.commands).singleElement().satisfies(command -> {
            assertThat(command.engine()).isEqualTo(JourneyResultEngine.LLM);
            assertThat(command.orderedRefs()).containsExactly("place:002", "place:001");
        });
        assertThat(telemetry.toString()).doesNotContain("한옥 산책을 구성해요", "골목을 이어봐요");
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "duplicate", "provider-error", "unknown-ref"})
    void malformedDuplicateOrProviderErrorStreamUsesCandidateOnlyBaseline(String mode) throws Exception {
        String body = "event: text.delta\ndata: {\"text\":\"임시 설명이에요.\"}\n\n" + switch (mode) {
            case "duplicate" -> finalProposal() + finalProposal();
            case "provider-error" -> "event: error\ndata: {\"code\":\"AI_QUOTA_EXCEEDED\"}\n\n";
            case "unknown-ref" -> finalProposal().replace("place:002", "private-ref");
            default -> "event: proposal\ndata: broken-json\n\n";
        };
        server = server(exchange -> respondStream(exchange, body));
        var resultStore = new RecordingResultStore(PersistJourneyResultResult.PERSISTED);
        var telemetry = new ArrayList<WorkerTelemetryEvent>();
        var outcome = streamingWorker(new RecordingRunStore(), resultStore, aiClient(telemetry),
                new JourneyRunEventBuffer(64, Duration.ofSeconds(15)), telemetry).process(request());
        assertThat(outcome).isEqualTo(JourneyWorkerOutcome.COMPLETED_BASELINE);
        assertThat(resultStore.commands).singleElement().satisfies(command -> {
            assertThat(command.engine()).isEqualTo(JourneyResultEngine.BASELINE);
            assertThat(command.orderedRefs()).containsOnly("place:001", "place:002");
            assertThat(command.degradedReason().name()).isEqualTo(mode.equals("provider-error") ? "AI_QUOTA_EXCEEDED" : "AI_INVALID_RESPONSE");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "generation"})
    void cancelledOrReplacedRunNeverPublishesLateDeltaOrPersistsLateResult(String mode) throws Exception {
        var runStore = new RecordingRunStore();
        var resultStore = new RecordingResultStore(PersistJourneyResultResult.PERSISTED);
        var sink = new JourneyRunEventBuffer(64, Duration.ofSeconds(15));
        var ai = new AiProposalClient() {
            @Override public JourneyWorkerPlan propose(JourneyWorkerRequest ignored, CandidatePayload payload) {
                return new JourneyWorkerPlan(List.of("place:001"), "PROPOSAL");
            }
            public JourneyWorkerPlan propose(JourneyWorkerRequest ignored, CandidatePayload payload, Consumer<String> delta) {
                delta.accept("취소 전 설명");
                if (mode.equals("cancel")) {
                    runStore.status = JourneyRunStatus.CANCELLED;
                    sink.terminal(request().runId(), "CANCELLED", null);
                } else {
                    runStore.generation++;
                }
                delta.accept("late-private-description");
                return new JourneyWorkerPlan(List.of("place:001"), "PROPOSAL");
            }
        };
        var outcome = streamingWorker(runStore, resultStore, ai, sink, new ArrayList<>()).process(request());
        assertThat(outcome).isEqualTo(JourneyWorkerOutcome.DISCARDED_LATE_RESULT);
        assertThat(resultStore.commands).isEmpty();
        assertThat(runStore.finished).isEmpty();
        assertThat(sink.replay(request().runId(), null).events().stream()
                .filter(event -> event.type() == JourneyRunEventType.TEXT_DELTA).map(event -> event.data()))
                .hasSize(1).allMatch(data -> data.contains("취소 전 설명") && !data.contains("late-private"));
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

    private JourneyWorkerService streamingWorker(RecordingRunStore runStore, RecordingResultStore resultStore,
            AiProposalClient ai, JourneyRunEventSink sink, List<WorkerTelemetryEvent> telemetry) throws Exception {
        var provider = new InMemoryJourneyCandidateProvider();
        provider.put("dataset-2026-09-16", "seoul-jongno", List.of(new JourneyCandidate("place:001"), new JourneyCandidate("place:002")));
        return new JourneyWorkerService(runStore, provider, ai, new DefaultBaselinePlanner(),
                resultStore, telemetry::add, sink, FIXED_CLOCK);
    }

    private static String finalProposal() {
        return "event: proposal\ndata: {\"proposal\":{\"runId\":\"%s\",\"orderedRefs\":[\"place:002\",\"place:001\"],\"outcome\":\"PROPOSAL\"}}\n\n".formatted(request().runId());
    }

    private static void respondStream(HttpExchange exchange, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
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
        JourneyRunStatus status = JourneyRunStatus.RUNNING;
        int generation = 2;

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
            if (generation != command.expectedGeneration()) {
                throw new com.yrootlab.onmaru.journey.run.RunTransitionConflictException();
            }
            generation = command.expectedGeneration() + 1;
            return result(JourneyRunStatus.RUNNING, command.nextStage(), command.expectedGeneration() + 1);
        }

        @Override
        public RunCommandResult finish(FinishRunCommand command) {
            finished.add(command);
            return result(command.terminalStatus(), null, command.expectedGeneration() + 1);
        }

        @Override
        public Optional<JourneyRunSnapshot> find(UUID runId, String actorKey) {
            var request = request();
            return Optional.of(new JourneyRunSnapshot(
                    runId,
                    request.explorationId(),
                    actorKey,
                    request.baseVersion(),
                    status,
                    JourneyRunStage.RETRIEVING,
                    null,
                    Instant.parse("2026-09-16T01:00:00Z"),
                    request.deadlineAt(),
                    Instant.parse("2026-09-16T01:00:01Z"),
                    generation,
                    null,
                    "LLM"));
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

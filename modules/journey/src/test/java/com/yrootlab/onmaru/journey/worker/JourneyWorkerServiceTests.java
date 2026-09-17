package com.yrootlab.onmaru.journey.worker;

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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyWorkerServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EXPLORATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final RecordingRunStore runStore = new RecordingRunStore();
    private final RecordingResultStore resultStore = new RecordingResultStore();
    private final RecordingTelemetry telemetry = new RecordingTelemetry();
    private final JourneyWorkerRequest request = new JourneyWorkerRequest(
            RUN_ID,
            EXPLORATION_ID,
            "MEMBER:123",
            1,
            "dataset-2026-09-16",
            "seoul-jongno",
            "조용한 한옥 코스",
            1,
            "req-001",
            "4bf92f3577b34da6a3ce929d0e0e4736",
            NOW.plusSeconds(20));

    @Test
    void completesBaselineWhenAiTimesOutAfterCandidateRetrieval() {
        var service = new JourneyWorkerService(
                runStore,
                ignored -> new CandidatePayload(
                        "dataset-2026-09-16",
                        List.of(new JourneyCandidate("place:001"), new JourneyCandidate("place:002"))),
                (actualRequest, ignored) -> {
                    assertThat(actualRequest.requestId()).isEqualTo("req-001");
                    throw new AiProposalException(WorkerDegradedReason.AI_TIMEOUT);
                },
                candidates -> new JourneyWorkerPlan(List.of("place:001", "place:002"), "INITIAL_BOARD"),
                resultStore,
                telemetry);

        var outcome = service.process(request);

        assertThat(outcome).isEqualTo(JourneyWorkerOutcome.COMPLETED_BASELINE);
        assertThat(resultStore.commands).containsExactly(new PersistJourneyResultCommand(
                RUN_ID,
                EXPLORATION_ID,
                1,
                JourneyResultEngine.BASELINE,
                WorkerDegradedReason.AI_TIMEOUT,
                List.of("place:001", "place:002"),
                "INITIAL_BOARD"));
        assertThat(runStore.finished).hasSize(1);
        assertThat(runStore.finished.get(0).terminalStatus()).isEqualTo(JourneyRunStatus.COMPLETED);
        assertThat(runStore.finished.get(0).outcome()).isEqualTo("INITIAL_BOARD");
        assertThat(telemetry.events).contains(new WorkerTelemetryEvent(
                "journey.worker.completed",
                Map.of(
                        "run.id", RUN_ID.toString(),
                        "engine", "BASELINE",
                        "degraded.reason", "AI_TIMEOUT")));
    }

    @Test
    void completesBaselineWhenAiQuotaIsExceeded() {
        var service = new JourneyWorkerService(
                runStore,
                ignored -> new CandidatePayload(
                        "dataset-2026-09-16",
                        List.of(new JourneyCandidate("place:001"))),
                (actualRequest, ignored) -> {
                    assertThat(actualRequest.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
                    throw new AiProposalException(WorkerDegradedReason.AI_QUOTA_EXCEEDED);
                },
                candidates -> new JourneyWorkerPlan(List.of("place:001"), "INITIAL_BOARD"),
                resultStore,
                telemetry);

        var outcome = service.process(request);

        assertThat(outcome).isEqualTo(JourneyWorkerOutcome.COMPLETED_BASELINE);
        assertThat(resultStore.commands.get(0).engine()).isEqualTo(JourneyResultEngine.BASELINE);
        assertThat(resultStore.commands.get(0).degradedReason()).isEqualTo(WorkerDegradedReason.AI_QUOTA_EXCEEDED);
    }

    @Test
    void discardsLateResultWithoutTerminalOverwriteWhenPersistSeesTerminalRun() {
        resultStore.nextResult = PersistJourneyResultResult.DISCARDED_TERMINAL;
        var service = new JourneyWorkerService(
                runStore,
                ignored -> new CandidatePayload(
                        "dataset-2026-09-16",
                        List.of(new JourneyCandidate("place:001"))),
                (actualRequest, ignored) -> new JourneyWorkerPlan(List.of("place:001"), "PROPOSAL"),
                candidates -> new JourneyWorkerPlan(List.of("place:001"), "INITIAL_BOARD"),
                resultStore,
                telemetry);

        var outcome = service.process(request);

        assertThat(outcome).isEqualTo(JourneyWorkerOutcome.DISCARDED_LATE_RESULT);
        assertThat(resultStore.commands).hasSize(1);
        assertThat(runStore.finished).isEmpty();
        assertThat(telemetry.events).contains(new WorkerTelemetryEvent(
                "journey.worker.late_result_discarded",
                Map.of(
                        "run.id", RUN_ID.toString(),
                        "reason", "DISCARDED_TERMINAL")));
    }

    private static final class RecordingRunStore implements JourneyRunStore {
        final List<ClaimRunCommand> claimed = new ArrayList<>();
        final List<AdvanceRunStageCommand> advanced = new ArrayList<>();
        final List<FinishRunCommand> finished = new ArrayList<>();

        @Override
        public RunCommandResult create(CreateRunCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunCommandResult claim(ClaimRunCommand command) {
            claimed.add(command);
            return result(JourneyRunStatus.RUNNING, null, 2);
        }

        @Override
        public RunCommandResult advance(AdvanceRunStageCommand command) {
            advanced.add(command);
            return result(JourneyRunStatus.RUNNING, command.nextStage(), command.expectedGeneration() + 1);
        }

        @Override
        public RunCommandResult finish(FinishRunCommand command) {
            finished.add(command);
            return result(command.terminalStatus(), null, command.expectedGeneration() + 1);
        }

        @Override
        public Optional<JourneyRunSnapshot> find(UUID runId, String actorKey) {
            return Optional.of(new JourneyRunSnapshot(
                    runId,
                    EXPLORATION_ID,
                    actorKey,
                    1,
                    JourneyRunStatus.RUNNING,
                    JourneyRunStage.RETRIEVING,
                    null,
                    NOW,
                    NOW.plusSeconds(20),
                    NOW.plusSeconds(1),
                    2,
                    null,
                    "LLM"));
        }

        private RunCommandResult result(JourneyRunStatus status, JourneyRunStage stage, int generation) {
            return new RunCommandResult(
                    new RunCommandReceipt(RUN_ID, status, stage, null, generation),
                    false);
        }
    }

    private static final class RecordingResultStore implements JourneyResultStore {
        final List<PersistJourneyResultCommand> commands = new ArrayList<>();
        PersistJourneyResultResult nextResult = PersistJourneyResultResult.PERSISTED;

        @Override
        public PersistJourneyResultResult persist(PersistJourneyResultCommand command) {
            commands.add(command);
            return nextResult;
        }
    }

    private static final class RecordingTelemetry implements JourneyWorkerTelemetry {
        final List<WorkerTelemetryEvent> events = new ArrayList<>();

        @Override
        public void record(WorkerTelemetryEvent event) {
            events.add(event);
        }
    }
}

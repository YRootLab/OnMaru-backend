package com.yrootlab.onmaru.journey.worker;

import com.yrootlab.onmaru.journey.run.AdvanceRunStageCommand;
import com.yrootlab.onmaru.journey.run.ClaimRunCommand;
import com.yrootlab.onmaru.journey.run.FinishRunCommand;
import com.yrootlab.onmaru.journey.run.JourneyRunStage;
import com.yrootlab.onmaru.journey.run.JourneyRunStatus;
import com.yrootlab.onmaru.journey.run.JourneyRunStore;
import com.yrootlab.onmaru.journey.run.RunCommandReceipt;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class JourneyWorkerService {

    private final JourneyRunStore runStore;
    private final JourneyCandidateProvider candidateProvider;
    private final AiProposalClient aiProposalClient;
    private final BaselinePlanner baselinePlanner;
    private final JourneyResultStore resultStore;
    private final JourneyWorkerTelemetry telemetry;

    public JourneyWorkerService(
            JourneyRunStore runStore,
            JourneyCandidateProvider candidateProvider,
            AiProposalClient aiProposalClient,
            BaselinePlanner baselinePlanner,
            JourneyResultStore resultStore,
            JourneyWorkerTelemetry telemetry) {
        this.runStore = runStore;
        this.candidateProvider = candidateProvider;
        this.aiProposalClient = aiProposalClient;
        this.baselinePlanner = baselinePlanner;
        this.resultStore = resultStore;
        this.telemetry = telemetry;
    }

    public JourneyWorkerOutcome process(JourneyWorkerRequest request) {
        requireRequest(request);
        recordStarted(request);
        var receipt = claim(request);
        receipt = advance(request, receipt, null, JourneyRunStage.RETRIEVING);
        var candidates = candidateProvider.retrieve(request);
        recordCandidatesRetrieved(request, candidates);
        receipt = advance(request, receipt, JourneyRunStage.RETRIEVING, JourneyRunStage.VALIDATING);

        WorkerDegradedReason degradedReason = null;
        JourneyResultEngine engine = JourneyResultEngine.LLM;
        JourneyWorkerPlan plan;
        try {
            plan = aiProposalClient.propose(request, candidates);
        } catch (AiProposalException exception) {
            degradedReason = exception.degradedReason();
            engine = JourneyResultEngine.BASELINE;
            plan = baselinePlanner.plan(candidates);
        }

        receipt = advance(request, receipt, JourneyRunStage.VALIDATING, JourneyRunStage.PERSISTING);
        var persistResult = resultStore.persist(new PersistJourneyResultCommand(
                request.runId(),
                request.explorationId(),
                request.baseVersion(),
                engine,
                degradedReason,
                plan.orderedRefs(),
                plan.outcome()));
        if (persistResult != PersistJourneyResultResult.PERSISTED) {
            recordLateDiscard(request, persistResult);
            return JourneyWorkerOutcome.DISCARDED_LATE_RESULT;
        }

        runStore.finish(new FinishRunCommand(
                request.runId(),
                request.actorKey(),
                UUID.randomUUID(),
                requestHash(request, "finish"),
                receipt.generation(),
                JourneyRunStatus.COMPLETED,
                plan.outcome(),
                null,
                Instant.now()));
        recordCompleted(request, engine, degradedReason);
        return engine == JourneyResultEngine.BASELINE
                ? JourneyWorkerOutcome.COMPLETED_BASELINE
                : JourneyWorkerOutcome.COMPLETED_LLM;
    }

    private RunCommandReceipt claim(JourneyWorkerRequest request) {
        return runStore.claim(new ClaimRunCommand(
                request.runId(),
                request.actorKey(),
                UUID.randomUUID(),
                requestHash(request, "claim"),
                request.expectedGeneration(),
                Instant.now())).receipt();
    }

    private RunCommandReceipt advance(
            JourneyWorkerRequest request,
            RunCommandReceipt receipt,
            JourneyRunStage expectedStage,
            JourneyRunStage nextStage) {
        return runStore.advance(new AdvanceRunStageCommand(
                request.runId(),
                request.actorKey(),
                UUID.randomUUID(),
                requestHash(request, "advance:" + nextStage),
                receipt.generation(),
                expectedStage,
                nextStage)).receipt();
    }

    private void recordCompleted(
            JourneyWorkerRequest request,
            JourneyResultEngine engine,
            WorkerDegradedReason degradedReason) {
        var attributes = new LinkedHashMap<String, String>();
        attributes.put("run.id", request.runId().toString());
        attributes.put("engine", engine.name());
        if (degradedReason != null) {
            attributes.put("degraded.reason", degradedReason.name());
        }
        telemetry.record(new WorkerTelemetryEvent("journey.worker.completed", attributes));
    }

    private void recordStarted(JourneyWorkerRequest request) {
        telemetry.record(new WorkerTelemetryEvent(
                "journey.worker.started",
                Map.of("run.id", request.runId().toString())));
    }

    private void recordCandidatesRetrieved(JourneyWorkerRequest request, CandidatePayload candidates) {
        telemetry.record(new WorkerTelemetryEvent(
                "journey.worker.candidates_retrieved",
                Map.of(
                        "run.id", request.runId().toString(),
                        "candidate.count", Integer.toString(candidates.candidates().size()))));
    }

    private void recordLateDiscard(JourneyWorkerRequest request, PersistJourneyResultResult persistResult) {
        telemetry.record(new WorkerTelemetryEvent(
                "journey.worker.late_result_discarded",
                Map.of(
                        "run.id", request.runId().toString(),
                        "reason", persistResult.name())));
    }

    private String requestHash(JourneyWorkerRequest request, String operation) {
        return request.runId() + ":" + request.baseVersion() + ":" + operation;
    }

    private void requireRequest(JourneyWorkerRequest request) {
        if (request == null || request.runId() == null || request.explorationId() == null
                || request.actorKey() == null || request.actorKey().isBlank()
                || request.expectedGeneration() < 1 || request.deadlineAt() == null) {
            throw new IllegalArgumentException("journey worker request is invalid");
        }
    }
}

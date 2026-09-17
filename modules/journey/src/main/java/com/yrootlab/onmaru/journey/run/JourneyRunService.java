package com.yrootlab.onmaru.journey.run;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public final class JourneyRunService {

    private static final Duration RUN_DEADLINE = Duration.ofSeconds(20);
    private final JourneyRunStore store;

    public JourneyRunService(JourneyRunStore store) {
        this.store = store;
    }

    public RunCommandResult create(CreateRunCommand command) {
        requireEnvelope(command.commandKey(), command.actorKey(), command.requestHash());
        if (command.runId() == null || command.explorationId() == null || command.baseVersion() < 0
                || command.engine() == null || command.engine().isBlank()
                || command.createdAt() == null || command.deadlineAt() == null
                || !RUN_DEADLINE.equals(Duration.between(command.createdAt(), command.deadlineAt()))) {
            throw new IllegalArgumentException("run create command is invalid");
        }
        return store.create(command);
    }

    public RunCommandResult claim(ClaimRunCommand command) {
        requireEnvelope(command.commandKey(), command.actorKey(), command.requestHash());
        requireRunVersion(command.runId(), command.expectedGeneration());
        if (command.startedAt() == null) {
            throw new IllegalArgumentException("startedAt is required");
        }
        return store.claim(command);
    }

    public RunCommandResult advance(AdvanceRunStageCommand command) {
        requireEnvelope(command.commandKey(), command.actorKey(), command.requestHash());
        requireRunVersion(command.runId(), command.expectedGeneration());
        if (command.nextStage() == null || !command.nextStage().follows(command.expectedStage())) {
            throw new IllegalArgumentException("run stage must advance exactly one step");
        }
        return store.advance(command);
    }

    public RunCommandResult finish(FinishRunCommand command) {
        requireEnvelope(command.commandKey(), command.actorKey(), command.requestHash());
        requireRunVersion(command.runId(), command.expectedGeneration());
        if (command.terminalStatus() == null || !command.terminalStatus().isTerminal()) {
            throw new IllegalArgumentException("terminal status is required");
        }
        if (command.finishedAt() == null) {
            throw new IllegalArgumentException("finishedAt is required");
        }
        if (command.terminalStatus() == JourneyRunStatus.COMPLETED
                && (command.outcome() == null || command.outcome().isBlank())) {
            throw new IllegalArgumentException("completed run outcome is required");
        }
        if (command.terminalStatus() != JourneyRunStatus.COMPLETED && command.outcome() != null) {
            throw new IllegalArgumentException("failed or cancelled run outcome must be null");
        }
        if (command.terminalStatus() == JourneyRunStatus.FAILED
                && (command.errorCode() == null || command.errorCode().isBlank())) {
            throw new IllegalArgumentException("failed run error code is required");
        }
        return store.finish(command);
    }

    public Optional<JourneyRunSnapshot> find(UUID runId, String actorKey) {
        if (runId == null || actorKey == null || actorKey.isBlank()) {
            return Optional.empty();
        }
        return store.find(runId, actorKey);
    }

    private void requireEnvelope(UUID key, String actorKey, String requestHash) {
        if (key == null || actorKey == null || actorKey.isBlank() || requestHash == null || requestHash.isBlank()) {
            throw new IllegalArgumentException("command key, actor key and request hash are required");
        }
    }

    private void requireRunVersion(UUID runId, int expectedGeneration) {
        if (runId == null || expectedGeneration < 1) {
            throw new IllegalArgumentException("run id and positive generation are required");
        }
    }
}

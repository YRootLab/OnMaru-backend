package com.yrootlab.onmaru.journey.exploration;

import java.time.Instant;
import java.util.UUID;

public record ExplorationRun(
        UUID id,
        ExplorationRunStatus status,
        String engine,
        String stage,
        ExplorationRunOutcome outcome,
        ExplorationClarification clarification,
        Instant createdAt,
        Instant startedAt,
        Instant deadlineAt,
        String degradedReason,
        long retryAfterMs,
        String errorCode) {

    public ExplorationRun(
            UUID id,
            ExplorationRunStatus status,
            String engine,
            String stage,
            ExplorationRunOutcome outcome,
            ExplorationClarification clarification,
            Instant createdAt,
            Instant startedAt,
            Instant deadlineAt) {
        this(id, status, engine, stage, outcome, clarification, createdAt, startedAt, deadlineAt, null, 0L, null);
    }

    public ExplorationRun(
            UUID id,
            ExplorationRunStatus status,
            String engine,
            ExplorationRunOutcome outcome,
            ExplorationClarification clarification,
            Instant createdAt,
            Instant startedAt,
            Instant deadlineAt) {
        this(id, status, engine, null, outcome, clarification, createdAt, startedAt, deadlineAt, null, 0L, null);
    }

    boolean isActive() {
        return status == ExplorationRunStatus.QUEUED || status == ExplorationRunStatus.RUNNING;
    }

    boolean isTerminal() {
        return status == ExplorationRunStatus.COMPLETED
                || status == ExplorationRunStatus.FAILED
                || status == ExplorationRunStatus.CANCELLED;
    }
}

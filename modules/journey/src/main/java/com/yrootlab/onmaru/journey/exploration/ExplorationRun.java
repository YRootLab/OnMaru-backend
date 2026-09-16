package com.yrootlab.onmaru.journey.exploration;

import java.time.Instant;
import java.util.UUID;

public record ExplorationRun(
        UUID id,
        ExplorationRunStatus status,
        String engine,
        ExplorationRunOutcome outcome,
        ExplorationClarification clarification,
        Instant createdAt,
        Instant startedAt,
        Instant deadlineAt) {
}

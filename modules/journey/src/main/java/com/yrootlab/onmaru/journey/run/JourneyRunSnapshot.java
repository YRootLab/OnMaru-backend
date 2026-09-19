package com.yrootlab.onmaru.journey.run;

import java.time.Instant;
import java.util.UUID;

public record JourneyRunSnapshot(
        UUID id,
        UUID explorationId,
        String actorKey,
        int baseVersion,
        JourneyRunStatus status,
        JourneyRunStage stage,
        String outcome,
        Instant createdAt,
        Instant deadlineAt,
        Instant startedAt,
        int generation,
        String errorCode,
        String engine) {
}

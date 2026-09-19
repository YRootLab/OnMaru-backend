package com.yrootlab.onmaru.journey.run;

import java.time.Instant;
import java.util.UUID;

public record CreateRunCommand(
        UUID commandKey,
        String actorKey,
        String requestHash,
        UUID runId,
        UUID explorationId,
        int baseVersion,
        String engine,
        Instant createdAt,
        Instant deadlineAt) {
}

package com.yrootlab.onmaru.journey.run;

import java.time.Instant;
import java.util.UUID;

public record FinishRunCommand(
        UUID commandKey,
        String actorKey,
        UUID runId,
        String requestHash,
        int expectedGeneration,
        JourneyRunStatus terminalStatus,
        String outcome,
        String errorCode,
        Instant finishedAt) {
}

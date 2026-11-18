package com.yrootlab.onmaru.journey.run;

import com.yrootlab.onmaru.journey.exploration.ExplorationClarification;
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
        Instant finishedAt,
        ExplorationClarification clarification) {

    public FinishRunCommand(
            UUID commandKey,
            String actorKey,
            UUID runId,
            String requestHash,
            int expectedGeneration,
            JourneyRunStatus terminalStatus,
            String outcome,
            String errorCode,
            Instant finishedAt) {
        this(commandKey, actorKey, runId, requestHash, expectedGeneration, terminalStatus, outcome, errorCode, finishedAt, null);
    }
}

package com.yrootlab.onmaru.journey.run;

import java.time.Instant;
import java.util.UUID;

public record ClaimRunCommand(
        UUID commandKey,
        String actorKey,
        UUID runId,
        String requestHash,
        int expectedGeneration,
        Instant startedAt) {
}

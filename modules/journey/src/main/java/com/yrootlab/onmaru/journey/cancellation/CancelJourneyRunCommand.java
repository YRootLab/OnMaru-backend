package com.yrootlab.onmaru.journey.cancellation;

import java.time.Instant;
import java.util.UUID;

public record CancelJourneyRunCommand(
        UUID commandKey,
        String actorKey,
        UUID runId,
        String requestHash,
        Instant cancelledAt) {
}

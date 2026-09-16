package com.yrootlab.onmaru.journey.worker;

import java.time.Instant;
import java.util.UUID;

public record JourneyWorkerRequest(
        UUID runId,
        UUID explorationId,
        String actorKey,
        int expectedGeneration,
        String datasetRevision,
        String regionCode,
        String queryText,
        int baseVersion,
        String requestId,
        String traceId,
        Instant deadlineAt) {
}

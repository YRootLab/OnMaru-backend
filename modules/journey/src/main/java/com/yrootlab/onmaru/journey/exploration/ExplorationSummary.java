package com.yrootlab.onmaru.journey.exploration;

import java.time.Instant;
import java.util.UUID;

public record ExplorationSummary(
        UUID explorationId,
        String title,
        String lastUserQueryPreview,
        ExplorationRunStatus latestRunStatus,
        int candidateCount,
        int pinnedCount,
        Instant updatedAt) {
}

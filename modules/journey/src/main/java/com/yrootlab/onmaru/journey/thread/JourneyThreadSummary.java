package com.yrootlab.onmaru.journey.thread;

import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunStatus;

import java.time.Instant;
import java.util.UUID;

public record JourneyThreadSummary(
        UUID threadId,
        UUID explorationId,
        String title,
        String lastUserQueryPreview,
        ExplorationRunOutcome lastOutcome,
        ExplorationRunStatus latestRunStatus,
        UUID savedJourneyId,
        int candidateCount,
        int pinnedCount,
        Instant updatedAt) {

    public static JourneyThreadSummary from(JourneyThread thread) {
        return new JourneyThreadSummary(
                thread.threadId(),
                thread.explorationId(),
                thread.title(),
                thread.lastUserQueryPreview(),
                thread.lastOutcome(),
                thread.latestRunStatus(),
                thread.savedJourneyId(),
                thread.candidateCount(),
                thread.pinnedCount(),
                thread.updatedAt());
    }
}

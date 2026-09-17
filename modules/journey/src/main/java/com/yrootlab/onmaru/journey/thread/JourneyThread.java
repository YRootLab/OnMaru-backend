package com.yrootlab.onmaru.journey.thread;

import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunStatus;

import java.time.Instant;
import java.util.UUID;

public record JourneyThread(
        UUID threadId,
        UUID memberId,
        UUID explorationId,
        String title,
        String lastUserQueryPreview,
        ExplorationRunOutcome lastOutcome,
        ExplorationRunStatus latestRunStatus,
        UUID savedJourneyId,
        int pinnedCount,
        int candidateCount,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public JourneyThread withUpdatedState(
            String title,
            String lastUserQueryPreview,
            ExplorationRunOutcome lastOutcome,
            ExplorationRunStatus latestRunStatus,
            UUID savedJourneyId,
            int pinnedCount,
            int candidateCount,
            Instant updatedAt) {
        return new JourneyThread(
                threadId,
                memberId,
                explorationId,
                title != null ? title : this.title,
                lastUserQueryPreview != null ? lastUserQueryPreview : this.lastUserQueryPreview,
                lastOutcome != null ? lastOutcome : this.lastOutcome,
                latestRunStatus != null ? latestRunStatus : this.latestRunStatus,
                savedJourneyId != null ? savedJourneyId : this.savedJourneyId,
                pinnedCount,
                candidateCount,
                createdAt,
                updatedAt,
                deletedAt);
    }

    public JourneyThread markDeleted(Instant deletedAt) {
        return new JourneyThread(
                threadId,
                memberId,
                explorationId,
                title,
                lastUserQueryPreview,
                lastOutcome,
                latestRunStatus,
                savedJourneyId,
                pinnedCount,
                candidateCount,
                createdAt,
                updatedAt,
                deletedAt);
    }
}

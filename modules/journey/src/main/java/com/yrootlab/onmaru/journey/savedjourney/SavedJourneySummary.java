package com.yrootlab.onmaru.journey.savedjourney;

import java.time.Instant;
import java.util.UUID;

public record SavedJourneySummary(
        UUID savedJourneyId,
        String title,
        UUID sourceExplorationId,
        int sourceVersion,
        Instant savedAt,
        int candidateCount) {

    static SavedJourneySummary from(SavedJourney journey) {
        return new SavedJourneySummary(
                journey.savedJourneyId(),
                journey.title(),
                journey.sourceExplorationId(),
                journey.sourceVersion(),
                journey.savedAt(),
                journey.candidateCount());
    }
}

package com.yrootlab.onmaru.journey.savedjourney;

import java.time.Instant;
import java.util.UUID;

public record SavedJourney(
        UUID savedJourneyId,
        String title,
        UUID sourceExplorationId,
        int sourceVersion,
        Instant savedAt,
        int candidateCount,
        SavedJourneySnapshot snapshot) {
}

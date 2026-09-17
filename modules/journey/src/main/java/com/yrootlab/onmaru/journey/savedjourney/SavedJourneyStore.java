package com.yrootlab.onmaru.journey.savedjourney;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedJourneyStore {
    SavedJourneySaveResult save(UUID memberId, SavedJourneySnapshot snapshot, Instant savedAt, int limit);

    Optional<SavedJourney> find(UUID memberId, UUID savedJourneyId);

    List<SavedJourneySummary> list(UUID memberId);

    void delete(UUID memberId, UUID savedJourneyId);
}

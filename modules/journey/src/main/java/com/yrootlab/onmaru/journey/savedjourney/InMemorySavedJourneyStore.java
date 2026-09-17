package com.yrootlab.onmaru.journey.savedjourney;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemorySavedJourneyStore implements SavedJourneyStore {

    private final Map<UUID, Row> rows = new LinkedHashMap<>();

    @Override
    public synchronized SavedJourneySaveResult save(
            UUID memberId,
            SavedJourneySnapshot snapshot,
            Instant savedAt,
            int limit) {
        for (var row : rows.values()) {
            if (row.memberId().equals(memberId)
                    && row.journey().sourceExplorationId().equals(snapshot.sourceExplorationId())
                    && row.journey().sourceVersion() == snapshot.sourceVersion()) {
                return new SavedJourneySaveResult(row.journey(), false);
            }
        }
        if (countFor(memberId) >= limit) {
            throw new SavedJourneyLimitExceededException(limit);
        }
        var id = UUID.randomUUID();
        var journey = new SavedJourney(
                id,
                snapshot.title(),
                snapshot.sourceExplorationId(),
                snapshot.sourceVersion(),
                savedAt,
                snapshot.orderedRefs().size(),
                snapshot);
        rows.put(id, new Row(memberId, journey));
        return new SavedJourneySaveResult(journey, true);
    }

    @Override
    public synchronized Optional<SavedJourney> find(UUID memberId, UUID savedJourneyId) {
        return Optional.ofNullable(rows.get(savedJourneyId))
                .filter(row -> row.memberId().equals(memberId))
                .map(Row::journey);
    }

    @Override
    public synchronized List<SavedJourneySummary> list(UUID memberId) {
        return rows.values().stream()
                .filter(row -> row.memberId().equals(memberId))
                .map(Row::journey)
                .sorted(Comparator.comparing(SavedJourney::savedAt).reversed()
                        .thenComparing(SavedJourney::savedJourneyId, Comparator.reverseOrder()))
                .map(SavedJourneySummary::from)
                .toList();
    }

    @Override
    public synchronized void delete(UUID memberId, UUID savedJourneyId) {
        var row = rows.get(savedJourneyId);
        if (row == null || !row.memberId().equals(memberId)) {
            throw new SavedJourneyNotFoundException();
        }
        rows.remove(savedJourneyId);
    }

    public synchronized long countFor(UUID memberId) {
        return rows.values().stream().filter(row -> row.memberId().equals(memberId)).count();
    }

    public synchronized void clear() {
        rows.clear();
    }

    private record Row(UUID memberId, SavedJourney journey) {
    }
}

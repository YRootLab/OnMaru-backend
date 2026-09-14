package com.yrootlab.onmaru.journey.saved.place;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySavedPlaceStore implements SavedPlaceStore {

    private final Map<SavedPlaceKey, SavedPlaceState> savedPlaces = new ConcurrentHashMap<>();

    @Override
    public synchronized SavedPlaceState save(UUID memberId, String placeId, Instant savedAt, int limit) {
        var key = new SavedPlaceKey(memberId, placeId);
        var existing = savedPlaces.get(key);
        if (existing != null) {
            return existing;
        }
        if (countFor(memberId, SavedResourceType.PLACE) >= limit) {
            throw new SavedPlaceLimitExceededException(limit);
        }
        var state = new SavedPlaceState(
                "1.2",
                SavedResourceType.PLACE,
                placeId,
                placeId,
                true,
                savedAt);
        savedPlaces.put(key, state);
        return state;
    }

    @Override
    public void delete(UUID memberId, String placeId) {
        savedPlaces.remove(new SavedPlaceKey(memberId, placeId));
    }

    @Override
    public boolean savedBy(UUID memberId, String placeId) {
        return savedPlaces.containsKey(new SavedPlaceKey(memberId, placeId));
    }

    @Override
    public long countFor(UUID memberId, SavedResourceType resourceType) {
        return savedPlaces.keySet().stream()
                .filter(key -> key.memberId().equals(memberId))
                .count();
    }

    public Optional<SavedPlaceState> find(UUID memberId, String placeId) {
        return Optional.ofNullable(savedPlaces.get(new SavedPlaceKey(memberId, placeId)));
    }

    public void clear() {
        savedPlaces.clear();
    }

    private record SavedPlaceKey(UUID memberId, String placeId) {
    }
}

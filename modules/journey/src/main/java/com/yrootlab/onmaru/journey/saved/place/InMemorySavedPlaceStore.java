package com.yrootlab.onmaru.journey.saved.place;

import com.yrootlab.onmaru.journey.saved.list.SavedPlaceRecordSource;
import com.yrootlab.onmaru.journey.saved.list.SavedResourceRecord;
import com.yrootlab.onmaru.journey.saved.list.SavedResourceRecordSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySavedPlaceStore implements SavedPlaceStore, SavedPlaceRecordSource {

    private final Map<SavedPlaceKey, SavedRow> savedPlaces = new ConcurrentHashMap<>();

    @Override
    public synchronized SavedPlaceState save(UUID memberId, String placeId, Instant savedAt, int limit) {
        var key = new SavedPlaceKey(memberId, placeId);
        var existing = savedPlaces.get(key);
        if (existing != null) {
            return existing.state();
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
        savedPlaces.put(key, new SavedRow(UUID.randomUUID(), state));
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
        if (resourceType != SavedResourceType.PLACE) {
            return 0;
        }
        return savedPlaces.keySet().stream()
                .filter(key -> key.memberId().equals(memberId))
                .count();
    }

    public Optional<SavedPlaceState> find(UUID memberId, String placeId) {
        return Optional.ofNullable(savedPlaces.get(new SavedPlaceKey(memberId, placeId))).map(SavedRow::state);
    }

    @Override
    public List<SavedResourceRecord> records(UUID memberId, SavedResourceType resourceType) {
        if (resourceType != SavedResourceType.PLACE) {
            return List.of();
        }
        return savedPlaces.entrySet().stream()
                .filter(entry -> entry.getKey().memberId().equals(memberId))
                .map(entry -> new SavedResourceRecord(
                        entry.getValue().id(),
                        SavedResourceType.PLACE,
                        entry.getValue().state().resourceId(),
                        entry.getValue().state().savedAt()))
                .toList();
    }

    public void clear() {
        savedPlaces.clear();
    }

    private record SavedPlaceKey(UUID memberId, String placeId) {
    }

    private record SavedRow(UUID id, SavedPlaceState state) {
    }
}

package com.yrootlab.onmaru.journey.saved.odii;

import com.yrootlab.onmaru.journey.saved.list.SavedResourceRecord;
import com.yrootlab.onmaru.journey.saved.place.SavedResourceType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySavedOdiiStoryStore implements SavedOdiiStoryStore {

    private final Map<Key, SavedRow> states = new ConcurrentHashMap<>();

    @Override
    public synchronized SavedOdiiStoryState save(UUID memberId, String storyId, Instant savedAt, int limit) {
        var key = new Key(memberId, storyId);
        var existing = states.get(key);
        if (existing != null) {
            return existing.state();
        }
        if (records(memberId, SavedResourceType.ODII_STORY).size() >= limit) {
            throw new SavedOdiiStoryLimitExceededException(limit);
        }
        var state = new SavedOdiiStoryState(
                "1.2", SavedResourceType.ODII_STORY, storyId, storyId, true, savedAt);
        states.put(key, new SavedRow(UUID.randomUUID(), state));
        return state;
    }

    @Override
    public void delete(UUID memberId, String storyId) {
        states.remove(new Key(memberId, storyId));
    }

    @Override
    public boolean savedBy(UUID memberId, String storyId) {
        return states.containsKey(new Key(memberId, storyId));
    }

    public void clear() {
        states.clear();
    }

    @Override
    public List<SavedResourceRecord> records(UUID memberId, SavedResourceType resourceType) {
        if (resourceType != SavedResourceType.ODII_STORY) {
            return List.of();
        }
        return states.entrySet().stream()
                .filter(entry -> entry.getKey().memberId().equals(memberId))
                .map(entry -> new SavedResourceRecord(
                        entry.getValue().id(),
                        SavedResourceType.ODII_STORY,
                        entry.getValue().state().resourceId(),
                        entry.getValue().state().savedAt()))
                .toList();
    }

    private record Key(UUID memberId, String storyId) {
    }

    private record SavedRow(UUID id, SavedOdiiStoryState state) {
    }
}

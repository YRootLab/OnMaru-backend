package com.yrootlab.onmaru.catalog.application.query.detail;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySavedPlaceStateLookup implements SavedPlaceStateLookup {

    private final Set<SavedPlaceKey> savedPlaces = ConcurrentHashMap.newKeySet();

    @Override
    public boolean savedBy(Optional<UUID> memberId, String placeId) {
        return memberId
                .map(id -> savedPlaces.contains(new SavedPlaceKey(id, placeId)))
                .orElse(false);
    }

    public void save(UUID memberId, String placeId) {
        savedPlaces.add(new SavedPlaceKey(memberId, placeId));
    }

    public void clear() {
        savedPlaces.clear();
    }

    private record SavedPlaceKey(UUID memberId, String placeId) {
    }
}

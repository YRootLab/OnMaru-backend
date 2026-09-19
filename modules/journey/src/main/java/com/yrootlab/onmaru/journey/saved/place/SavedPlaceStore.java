package com.yrootlab.onmaru.journey.saved.place;

import java.time.Instant;
import java.util.UUID;

public interface SavedPlaceStore {

    SavedPlaceState save(UUID memberId, String placeId, Instant savedAt, int limit);

    void delete(UUID memberId, String placeId);

    boolean savedBy(UUID memberId, String placeId);

    long countFor(UUID memberId, SavedResourceType resourceType);
}

package com.yrootlab.onmaru.journey.saved.place;

import java.time.Clock;
import java.util.UUID;

public final class SavedPlaceService {

    private final SavedPlaceStore store;
    private final PlaceSaveEligibility eligibility;
    private final Clock clock;
    private final int limit;

    public SavedPlaceService(SavedPlaceStore store, PlaceSaveEligibility eligibility, Clock clock, int limit) {
        this.store = store;
        this.eligibility = eligibility;
        this.clock = clock;
        this.limit = limit;
    }

    public SavedPlaceState save(UUID memberId, String placeId) {
        if (!eligibility.isSaveable(placeId)) {
            throw new SavedPlaceNotFoundException();
        }
        return store.save(memberId, placeId, clock.instant(), limit);
    }

    public void delete(UUID memberId, String placeId) {
        store.delete(memberId, placeId);
    }
}

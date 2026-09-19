package com.yrootlab.onmaru.journey.saved.odii;

import java.time.Clock;
import java.util.UUID;

public final class SavedOdiiStoryService {

    private static final int SAVED_ODII_STORY_LIMIT = 300;

    private final SavedOdiiStoryStore store;
    private final OdiiStorySaveEligibility eligibility;
    private final Clock clock;

    public SavedOdiiStoryService(
            SavedOdiiStoryStore store,
            OdiiStorySaveEligibility eligibility,
            Clock clock) {
        this.store = store;
        this.eligibility = eligibility;
        this.clock = clock;
    }

    public SavedOdiiStoryState save(UUID memberId, String storyId) {
        if (!eligibility.isSaveable(storyId)) {
            throw new SavedOdiiStoryNotFoundException();
        }
        return store.save(memberId, storyId, clock.instant(), SAVED_ODII_STORY_LIMIT);
    }

    public void delete(UUID memberId, String storyId) {
        store.delete(memberId, storyId);
    }
}

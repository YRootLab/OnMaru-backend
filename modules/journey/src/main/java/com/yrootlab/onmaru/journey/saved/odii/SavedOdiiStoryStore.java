package com.yrootlab.onmaru.journey.saved.odii;

import com.yrootlab.onmaru.journey.saved.list.SavedOdiiRecordSource;

import java.time.Instant;
import java.util.UUID;

public interface SavedOdiiStoryStore extends SavedOdiiRecordSource {

    SavedOdiiStoryState save(UUID memberId, String storyId, Instant savedAt, int limit);

    void delete(UUID memberId, String storyId);

    boolean savedBy(UUID memberId, String storyId);
}

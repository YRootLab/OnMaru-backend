package com.yrootlab.onmaru.journey.saved.odii;

import com.yrootlab.onmaru.journey.saved.list.SavedResourceRecordSource;

import java.time.Instant;
import java.util.UUID;

public interface SavedOdiiStoryStore extends SavedResourceRecordSource {

    SavedOdiiStoryState save(UUID memberId, String storyId, Instant savedAt, int limit);

    void delete(UUID memberId, String storyId);

    boolean savedBy(UUID memberId, String storyId);
}

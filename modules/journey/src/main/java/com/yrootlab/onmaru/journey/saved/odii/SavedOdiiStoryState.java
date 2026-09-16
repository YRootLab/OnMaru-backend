package com.yrootlab.onmaru.journey.saved.odii;

import com.yrootlab.onmaru.journey.saved.place.SavedResourceType;

import java.time.Instant;

public record SavedOdiiStoryState(
        String schemaVersion,
        SavedResourceType resourceType,
        String resourceId,
        String storyId,
        boolean savedByMe,
        Instant savedAt) {
}

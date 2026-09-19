package com.yrootlab.onmaru.journey.timeline;

import java.util.Optional;
import java.util.UUID;

public interface TimelineOdiiStoryLookup {

    Optional<TimelineOdiiMetadata> lookup(String storyId, UUID memberId);
}

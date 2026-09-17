package com.yrootlab.onmaru.web.me.timeline;

import com.yrootlab.onmaru.audio.query.OdiiStoryNotFoundException;
import com.yrootlab.onmaru.audio.query.OdiiStoryQueryService;
import com.yrootlab.onmaru.audio.query.OdiiStoryUnavailableException;
import com.yrootlab.onmaru.journey.timeline.TimelineOdiiMetadata;
import com.yrootlab.onmaru.journey.timeline.TimelineOdiiStoryLookup;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class OdiiStoryTimelineAdapter implements TimelineOdiiStoryLookup {

    private final OdiiStoryQueryService odiiQueries;

    OdiiStoryTimelineAdapter(OdiiStoryQueryService odiiQueries) {
        this.odiiQueries = Objects.requireNonNull(odiiQueries, "odiiQueries");
    }

    @Override
    public Optional<TimelineOdiiMetadata> lookup(String storyId, UUID memberId) {
        try {
            var story = odiiQueries.savedStory(storyId, Optional.of(memberId));
            return Optional.of(new TimelineOdiiMetadata(story.title(), story.placeId()));
        } catch (OdiiStoryNotFoundException | OdiiStoryUnavailableException exception) {
            return Optional.empty();
        }
    }
}

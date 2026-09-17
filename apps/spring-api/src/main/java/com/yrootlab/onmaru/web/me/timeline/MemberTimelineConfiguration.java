package com.yrootlab.onmaru.web.me.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.audio.query.OdiiStoryQueryService;
import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.community.query.VisitReviewStore;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.journey.saved.odii.InMemorySavedOdiiStoryStore;
import com.yrootlab.onmaru.journey.saved.place.InMemorySavedPlaceStore;
import com.yrootlab.onmaru.journey.savedjourney.SavedJourneyStore;
import com.yrootlab.onmaru.journey.timeline.MemberTimelineService;
import com.yrootlab.onmaru.web.common.cursor.CursorCodec;
import com.yrootlab.onmaru.web.common.cursor.CursorSigningKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class MemberTimelineConfiguration {

    @Bean
    MemberTimelineCursorCodec memberTimelineCursorCodec(SecretProvider secrets, Clock clock) {
        var key = CursorSigningKey.fromUtf8(secrets.get("oauth.client-secret").current());
        return new MemberTimelineCursorCodec(new CursorCodec(new ObjectMapper(), key, clock), clock);
    }

    @Bean
    MemberTimelineService memberTimelineService(
            InMemorySavedPlaceStore placeStore,
            InMemoryPlaceDetailStore placeDetails,
            InMemorySavedOdiiStoryStore odiiStore,
            OdiiStoryQueryService odiiQueries,
            SavedJourneyStore journeyStore,
            VisitReviewStore reviewStore,
            Clock clock) {
        return new MemberTimelineService(
                placeStore,
                new PlaceDetailTimelineAdapter(placeDetails),
                odiiStore,
                new OdiiStoryTimelineAdapter(odiiQueries),
                journeyStore,
                new VisitReviewTimelineAdapter(reviewStore),
                clock);
    }
}

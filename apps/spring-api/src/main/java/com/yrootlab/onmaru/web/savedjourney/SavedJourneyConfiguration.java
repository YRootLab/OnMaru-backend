package com.yrootlab.onmaru.web.savedjourney;

import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjectionStatus;
import com.yrootlab.onmaru.journey.exploration.ExplorationService;
import com.yrootlab.onmaru.journey.savedjourney.InMemorySavedJourneyStore;
import com.yrootlab.onmaru.journey.savedjourney.ResourceAvailability;
import com.yrootlab.onmaru.journey.savedjourney.SavedJourneyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class SavedJourneyConfiguration {

    @Bean
    InMemorySavedJourneyStore savedJourneyStore() {
        return new InMemorySavedJourneyStore();
    }

    @Bean
    ResourceAvailability savedJourneyResourceAvailability(InMemoryPlaceDetailStore places) {
        return ref -> !"PLACE".equals(ref.type()) || places.findByPlaceId(ref.id())
                .filter(place -> place.status() == PlaceProjectionStatus.PUBLIC)
                .filter(place -> !place.ambiguousMapping())
                .isPresent();
    }

    @Bean
    SavedJourneyService savedJourneyService(
            InMemorySavedJourneyStore store,
            ExplorationService explorations,
            Clock clock,
            ResourceAvailability savedJourneyResourceAvailability) {
        return new SavedJourneyService(store, explorations, clock, savedJourneyResourceAvailability);
    }
}

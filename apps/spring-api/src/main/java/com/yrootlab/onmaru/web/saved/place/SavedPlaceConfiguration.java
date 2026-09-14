package com.yrootlab.onmaru.web.saved.place;

import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjectionStatus;
import com.yrootlab.onmaru.journey.saved.place.InMemorySavedPlaceStore;
import com.yrootlab.onmaru.journey.saved.place.PlaceSaveEligibility;
import com.yrootlab.onmaru.journey.saved.place.SavedPlaceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class SavedPlaceConfiguration {

    private static final int SAVED_PLACE_LIMIT = 500;

    @Bean
    InMemorySavedPlaceStore savedPlaceStore() {
        return new InMemorySavedPlaceStore();
    }

    @Bean
    PlaceSaveEligibility placeSaveEligibility(InMemoryPlaceDetailStore placeDetailStore) {
        return placeId -> placeDetailStore.findByPlaceId(placeId)
                .filter(projection -> projection.status() == PlaceProjectionStatus.PUBLIC)
                .filter(projection -> !projection.ambiguousMapping())
                .isPresent();
    }

    @Bean
    SavedPlaceService savedPlaceService(
            InMemorySavedPlaceStore savedPlaceStore,
            PlaceSaveEligibility placeSaveEligibility,
            Clock clock) {
        return new SavedPlaceService(savedPlaceStore, placeSaveEligibility, clock, SAVED_PLACE_LIMIT);
    }
}

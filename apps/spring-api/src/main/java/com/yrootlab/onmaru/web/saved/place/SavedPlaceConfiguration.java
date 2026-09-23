package com.yrootlab.onmaru.web.saved.place;

import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjectionStatus;
import com.yrootlab.onmaru.journey.saved.place.InMemorySavedPlaceStore;
import com.yrootlab.onmaru.journey.saved.place.PlaceSaveEligibility;
import com.yrootlab.onmaru.journey.saved.place.SavedPlaceService;
import com.yrootlab.onmaru.journey.saved.place.SavedPlaceStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class SavedPlaceConfiguration {

    private static final int SAVED_PLACE_LIMIT = 500;

    @Bean
    @ConditionalOnMissingBean(SavedPlaceStore.class)
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
            SavedPlaceStore savedPlaceStore,
            PlaceSaveEligibility placeSaveEligibility,
            Clock clock) {
        return new SavedPlaceService(savedPlaceStore, placeSaveEligibility, clock, SAVED_PLACE_LIMIT);
    }
}

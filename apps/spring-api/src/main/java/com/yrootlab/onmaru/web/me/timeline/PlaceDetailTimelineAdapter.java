package com.yrootlab.onmaru.web.me.timeline;

import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjectionStatus;
import com.yrootlab.onmaru.journey.timeline.TimelinePlaceLookup;
import com.yrootlab.onmaru.journey.timeline.TimelinePlaceMetadata;

import java.util.Objects;
import java.util.Optional;

final class PlaceDetailTimelineAdapter implements TimelinePlaceLookup {

    private final InMemoryPlaceDetailStore placeDetails;

    PlaceDetailTimelineAdapter(InMemoryPlaceDetailStore placeDetails) {
        this.placeDetails = Objects.requireNonNull(placeDetails, "placeDetails");
    }

    @Override
    public Optional<TimelinePlaceMetadata> lookup(String placeId) {
        return placeDetails.findByPlaceId(placeId)
                .filter(place -> place.status() == PlaceProjectionStatus.PUBLIC && !place.ambiguousMapping())
                .map(place -> new TimelinePlaceMetadata(
                        place.name(),
                        place.category(),
                        place.region().name(),
                        place.images().isEmpty() ? null : place.images().getFirst().url()));
    }
}

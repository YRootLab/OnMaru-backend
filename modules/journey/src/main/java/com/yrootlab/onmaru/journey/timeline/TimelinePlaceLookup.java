package com.yrootlab.onmaru.journey.timeline;

import java.util.Optional;

public interface TimelinePlaceLookup {

    Optional<TimelinePlaceMetadata> lookup(String placeId);
}

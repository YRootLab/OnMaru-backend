package com.yrootlab.onmaru.catalog.application.query.spatial;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface MapSavedStateLookup {

    boolean savedBy(Optional<UUID> memberId, String placeId);
}

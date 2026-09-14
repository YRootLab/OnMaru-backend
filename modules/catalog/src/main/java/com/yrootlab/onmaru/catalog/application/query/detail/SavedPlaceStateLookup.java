package com.yrootlab.onmaru.catalog.application.query.detail;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface SavedPlaceStateLookup {

    boolean savedBy(Optional<UUID> memberId, String placeId);
}

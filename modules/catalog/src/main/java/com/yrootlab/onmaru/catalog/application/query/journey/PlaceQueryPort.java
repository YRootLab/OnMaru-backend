package com.yrootlab.onmaru.catalog.application.query.journey;

import java.util.List;

public interface PlaceQueryPort {

    List<ApprovedPlaceLookupItem> lookupApprovedPlaces(String regionCode, String category, int limit);

    default List<ApprovedPlaceLookupItem> lookupApprovedPlaces(
            String regionCode,
            String category,
            List<String> allowedPlaceIds,
            List<String> excludedPlaceIds,
            int limit) {
        return lookupApprovedPlaces(regionCode, category, limit);
    }
}

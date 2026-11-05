package com.yrootlab.onmaru.catalog.application.query.journey;

import java.util.List;

public interface PlaceQueryPort {

    List<ApprovedPlaceLookupItem> lookupApprovedPlaces(String regionCode, String category, int limit);
}

package com.yrootlab.onmaru.catalog.application.query.detail;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryPlaceDetailStore implements PlaceDetailStore {

    private final Map<String, PlaceProjection> places = new ConcurrentHashMap<>();
    private volatile boolean unavailable;

    @Override
    public Optional<PlaceProjection> findByPlaceId(String placeId) {
        if (unavailable) {
            throw new PlaceDetailUnavailableException();
        }
        return Optional.ofNullable(places.get(placeId));
    }

    public void add(PlaceProjection projection) {
        places.put(projection.placeId(), projection);
    }

    public void clear() {
        places.clear();
        unavailable = false;
    }

    public void markUnavailable() {
        unavailable = true;
    }
}

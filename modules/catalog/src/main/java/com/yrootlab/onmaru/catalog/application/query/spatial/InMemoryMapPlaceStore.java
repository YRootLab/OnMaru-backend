package com.yrootlab.onmaru.catalog.application.query.spatial;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryMapPlaceStore implements MapPlaceStore {

    private final List<MapPlaceProjection> projections = new CopyOnWriteArrayList<>();
    private volatile boolean unavailable;

    @Override
    public List<MapPlaceProjection> findPublishedSnapshot() {
        if (unavailable) {
            throw new MapPlaceUnavailableException();
        }
        return List.copyOf(projections);
    }

    public void add(MapPlaceProjection projection) {
        projections.add(projection);
    }

    public void clear() {
        projections.clear();
        unavailable = false;
    }

    public void markUnavailable() {
        unavailable = true;
    }
}

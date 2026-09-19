package com.yrootlab.onmaru.catalog.application.query.hanok;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryHanokListStore implements HanokListStore {

    private final List<HanokListProjection> projections = new CopyOnWriteArrayList<>();
    private volatile boolean unavailable;

    @Override
    public List<HanokListProjection> findPublishedSnapshot() {
        if (unavailable) {
            throw new HanokListUnavailableException();
        }
        return List.copyOf(projections);
    }

    public void add(HanokListProjection projection) {
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

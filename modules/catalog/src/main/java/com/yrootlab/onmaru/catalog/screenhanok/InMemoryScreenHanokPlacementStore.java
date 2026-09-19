package com.yrootlab.onmaru.catalog.screenhanok;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class InMemoryScreenHanokPlacementStore implements ScreenHanokPlacementStore {

    private final AtomicReference<List<ScreenHanokPlacement>> placements = new AtomicReference<>(List.of());

    @Override
    public void publish(List<ScreenHanokPlacement> newPlacements) {
        placements.set(List.copyOf(newPlacements));
    }

    @Override
    public List<ScreenHanokPlacement> current() {
        return placements.get();
    }
}

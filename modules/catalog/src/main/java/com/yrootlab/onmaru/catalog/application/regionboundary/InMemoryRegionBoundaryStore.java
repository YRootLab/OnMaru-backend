package com.yrootlab.onmaru.catalog.application.regionboundary;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class InMemoryRegionBoundaryStore implements RegionBoundaryStore {

    private RegionBoundaryRevision activeRevision;

    @Override
    public synchronized void activate(RegionBoundaryRevision revision) {
        activeRevision = revision;
    }

    @Override
    public synchronized Optional<RegionBoundaryRevision> activeRevision() {
        return Optional.ofNullable(activeRevision);
    }

    @Override
    public synchronized List<RegionBoundaryProjection> resolve(double longitude, double latitude) {
        if (activeRevision == null) {
            return List.of();
        }
        return activeRevision.boundaries().stream()
                .filter(boundary -> boundary.contains(longitude, latitude))
                .sorted(Comparator.comparing(RegionBoundaryProjection::level))
                .toList();
    }
}

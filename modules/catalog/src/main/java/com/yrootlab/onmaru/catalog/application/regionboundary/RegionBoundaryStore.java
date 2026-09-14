package com.yrootlab.onmaru.catalog.application.regionboundary;

import java.util.List;
import java.util.Optional;

public interface RegionBoundaryStore {

    void activate(RegionBoundaryRevision revision);

    Optional<RegionBoundaryRevision> activeRevision();

    List<RegionBoundaryProjection> resolve(double longitude, double latitude);
}

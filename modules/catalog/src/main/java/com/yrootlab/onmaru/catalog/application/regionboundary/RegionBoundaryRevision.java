package com.yrootlab.onmaru.catalog.application.regionboundary;

import java.util.List;

public record RegionBoundaryRevision(
        String revisionId,
        RegionBoundarySourceManifest source,
        List<RegionBoundaryProjection> boundaries
) {

    public RegionBoundaryRevision {
        boundaries = List.copyOf(boundaries);
    }
}

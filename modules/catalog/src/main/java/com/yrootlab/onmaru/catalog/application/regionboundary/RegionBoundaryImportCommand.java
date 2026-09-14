package com.yrootlab.onmaru.catalog.application.regionboundary;

import java.util.List;

public record RegionBoundaryImportCommand(
        RegionBoundarySourceManifest source,
        List<RegionBoundaryCandidate> boundaries
) {

    public RegionBoundaryImportCommand {
        boundaries = List.copyOf(boundaries);
    }
}

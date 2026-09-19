package com.yrootlab.onmaru.catalog.application.regionboundary;

public record RegionBoundaryCandidate(
        String regionCode,
        String parentRegionCode,
        String name,
        RegionBoundaryLevel level,
        RegionBoundaryGeometry geometry
) {
}

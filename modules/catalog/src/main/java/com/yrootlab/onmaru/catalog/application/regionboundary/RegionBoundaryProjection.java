package com.yrootlab.onmaru.catalog.application.regionboundary;

public record RegionBoundaryProjection(
        String revisionId,
        String regionCode,
        String parentRegionCode,
        String name,
        RegionBoundaryLevel level,
        RegionBoundaryGeometry geometry
) {

    boolean contains(double longitude, double latitude) {
        return geometry.contains(longitude, latitude);
    }
}

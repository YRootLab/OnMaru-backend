package com.yrootlab.onmaru.community.region;

public record RegionProjection(
        String regionCode,
        String parentRegionCode,
        String name,
        RegionLevel level
) {
}

package com.yrootlab.onmaru.insights.query;

public record RegionRef(
        String regionCode,
        String name,
        String level,
        String parentRegionCode
) {
}

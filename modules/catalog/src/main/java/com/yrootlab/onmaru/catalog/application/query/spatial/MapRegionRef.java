package com.yrootlab.onmaru.catalog.application.query.spatial;

public record MapRegionRef(
        String regionCode,
        String name,
        String level,
        String parentRegionCode) {
}

package com.yrootlab.onmaru.community.query;

import java.util.List;

public record ReviewCoverage(
        ReviewCoverageStatus status,
        List<String> regionCodes) {

    public ReviewCoverage {
        regionCodes = List.copyOf(regionCodes);
    }
}

package com.yrootlab.onmaru.community.region;

public record RegionResolutionCandidate(
        RegionProjection region,
        double confidence
) {
}

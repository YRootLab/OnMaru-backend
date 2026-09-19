package com.yrootlab.onmaru.community.region;

import java.time.Instant;
import java.util.List;

public record RegionResolution(
        String schemaVersion,
        String regionRevision,
        RegionCoordinates coordinates,
        List<RegionResolutionCandidate> candidates,
        Instant resolvedAt
) {

    public RegionResolution {
        candidates = List.copyOf(candidates);
    }
}

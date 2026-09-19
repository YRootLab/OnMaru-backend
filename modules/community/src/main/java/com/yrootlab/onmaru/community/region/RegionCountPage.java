package com.yrootlab.onmaru.community.region;

import java.time.Instant;
import java.util.List;

public record RegionCountPage(
        String schemaVersion,
        String regionRevision,
        Instant countsAsOf,
        String parentRegionCode,
        long unassignedCount,
        List<RegionReviewCountItem> items
) {

    public RegionCountPage {
        items = List.copyOf(items);
    }
}

package com.yrootlab.onmaru.community.query;

import java.time.Instant;
import java.util.List;

public record ReviewPage(
        String schemaVersion,
        String queryKey,
        List<VisitReview> items,
        String nextCursor,
        boolean hasMore,
        Instant asOf,
        ReviewCoverage coverage) {

    public ReviewPage {
        items = List.copyOf(items);
    }
}

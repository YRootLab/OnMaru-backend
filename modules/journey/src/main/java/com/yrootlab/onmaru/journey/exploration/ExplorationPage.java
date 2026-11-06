package com.yrootlab.onmaru.journey.exploration;

import java.util.List;

public record ExplorationPage(
        String schemaVersion,
        List<ExplorationSummary> items,
        long totalCount,
        String nextCursor,
        boolean hasMore) {

    public ExplorationPage(List<ExplorationSummary> items, long totalCount, String nextCursor, boolean hasMore) {
        this("1.3", items, totalCount, nextCursor, hasMore);
    }
}

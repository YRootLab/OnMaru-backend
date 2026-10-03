package com.yrootlab.onmaru.journey.thread;

import java.util.List;

public record JourneyThreadPage(
        List<JourneyThreadSummary> items,
        long totalCount,
        String nextCursor,
        boolean hasMore) {
}

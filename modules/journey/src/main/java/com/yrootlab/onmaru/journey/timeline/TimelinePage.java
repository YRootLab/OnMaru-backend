package com.yrootlab.onmaru.journey.timeline;

import java.util.List;

public record TimelinePage(
        String schemaVersion,
        String month,
        List<TimelineDayGroup> groups,
        TimelineCursor nextCursor,
        boolean hasMore,
        int unavailableCount) {

    public TimelinePage {
        groups = List.copyOf(groups);
    }
}

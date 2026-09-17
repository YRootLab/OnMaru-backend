package com.yrootlab.onmaru.journey.timeline;

import java.util.List;

public record TimelineDayGroup(
        String date,
        List<TimelineItem> items) {

    public TimelineDayGroup {
        items = List.copyOf(items);
    }
}

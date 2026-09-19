package com.yrootlab.onmaru.journey.timeline;

import java.time.Instant;

public record TimelineItem(
        String id,
        TimelineItemType type,
        Instant occurredAt,
        String title,
        String subtitle,
        String thumbnailUrl,
        TimelineTarget target) {
}

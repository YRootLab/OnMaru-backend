package com.yrootlab.onmaru.community.moderation;

import java.time.Instant;
import java.util.List;

public record ModerationQueueSnapshot(
        Instant generatedAt,
        long oldestOpenReportAgeSeconds,
        List<ModerationQueueItem> items) {

    public ModerationQueueSnapshot {
        items = List.copyOf(items);
    }
}

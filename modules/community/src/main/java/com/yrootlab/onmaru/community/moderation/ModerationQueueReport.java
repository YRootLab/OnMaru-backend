package com.yrootlab.onmaru.community.moderation;

import java.time.Instant;

public record ModerationQueueReport(
        ReviewReportReason reason,
        String detail,
        Instant createdAt) {
}

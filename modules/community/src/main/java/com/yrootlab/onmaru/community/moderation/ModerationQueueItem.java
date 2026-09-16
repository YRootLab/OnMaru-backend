package com.yrootlab.onmaru.community.moderation;

import com.yrootlab.onmaru.community.query.VisitReviewStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ModerationQueueItem(
        UUID reviewId,
        String reviewText,
        VisitReviewStatus reviewStatus,
        ModerationQueuePriority priority,
        List<ModerationQueueReport> reports,
        List<ModerationAction> priorActions,
        Instant oldestOpenReportAt,
        long ageSeconds,
        Instant slaTargetAt,
        boolean overdue) {

    public ModerationQueueItem {
        reports = List.copyOf(reports);
        priorActions = List.copyOf(priorActions);
    }
}

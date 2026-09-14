package com.yrootlab.onmaru.community.moderation;

import java.time.Instant;
import java.util.UUID;

public record ReviewReport(
        UUID reportId,
        UUID reviewId,
        UUID reporterMemberId,
        ReviewReportReason reason,
        String detail,
        ReviewReportStatus status,
        Instant createdAt) {
}

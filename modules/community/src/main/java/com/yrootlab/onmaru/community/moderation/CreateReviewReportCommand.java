package com.yrootlab.onmaru.community.moderation;

public record CreateReviewReportCommand(ReviewReportReason reason, String detail) {
}

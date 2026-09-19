package com.yrootlab.onmaru.journey.timeline;

import java.time.Instant;

public record TimelineVisitReviewRecord(
        String reviewId,
        String placeId,
        String placeName,
        Instant createdAt,
        boolean isPublic) {
}

package com.yrootlab.onmaru.community.query;

import java.time.Instant;

public record VisitReview(
        String id,
        String placeId,
        String placeName,
        double lat,
        double lng,
        String text,
        Instant createdAt,
        boolean mine,
        int likeCount,
        boolean likedByMe) {
}

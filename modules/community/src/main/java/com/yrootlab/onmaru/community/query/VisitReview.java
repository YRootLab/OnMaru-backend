package com.yrootlab.onmaru.community.query;

import java.time.Instant;
import java.util.List;

public record VisitReview(
        String id,
        String placeId,
        String placeName,
        double lat,
        double lng,
        String text,
        String mood,
        Integer score,
        List<String> tags,
        Long visitorCount,
        Instant createdAt,
        boolean mine,
        int likeCount,
        boolean likedByMe) {
}

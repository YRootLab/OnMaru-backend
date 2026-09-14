package com.yrootlab.onmaru.community.query;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record VisitReviewProjection(
        UUID id,
        String placeId,
        String placeName,
        String regionCode,
        double lat,
        double lng,
        String text,
        Instant createdAt,
        UUID authorMemberId,
        Set<UUID> likedMemberIds,
        VisitReviewStatus status) {

    public VisitReviewProjection {
        likedMemberIds = Set.copyOf(likedMemberIds);
    }

    public VisitReviewProjection hidden() {
        return new VisitReviewProjection(
                id,
                placeId,
                placeName,
                regionCode,
                lat,
                lng,
                text,
                createdAt,
                authorMemberId,
                likedMemberIds,
                VisitReviewStatus.HIDDEN);
    }
}

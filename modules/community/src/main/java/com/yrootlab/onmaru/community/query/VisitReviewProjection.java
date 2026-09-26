package com.yrootlab.onmaru.community.query;

import java.time.Instant;
import java.util.List;
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
        String mood,
        Integer score,
        List<String> tags,
        Instant createdAt,
        UUID authorMemberId,
        Set<UUID> likedMemberIds,
        VisitReviewStatus status) {

    public VisitReviewProjection {
        tags = List.copyOf(tags == null ? List.of() : tags);
        likedMemberIds = Set.copyOf(likedMemberIds);
    }

    public VisitReviewProjection(
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
        this(id, placeId, placeName, regionCode, lat, lng, text, null, null, List.of(), createdAt, authorMemberId, likedMemberIds, status);
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
                mood,
                score,
                tags,
                createdAt,
                authorMemberId,
                likedMemberIds,
                VisitReviewStatus.HIDDEN);
    }
}

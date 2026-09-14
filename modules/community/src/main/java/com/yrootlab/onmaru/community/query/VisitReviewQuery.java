package com.yrootlab.onmaru.community.query;

import java.util.Optional;
import java.util.UUID;

public record VisitReviewQuery(
        ReviewQueryScope scope,
        String regionCode,
        String placeId,
        int limit,
        String cursor,
        Optional<UUID> memberId) {

    public VisitReviewQuery {
        memberId = memberId == null ? Optional.empty() : memberId;
    }

    public static VisitReviewQuery all(int limit, String cursor, Optional<UUID> memberId) {
        return new VisitReviewQuery(ReviewQueryScope.ALL, null, null, limit, cursor, memberId);
    }

    public static VisitReviewQuery region(String regionCode, int limit, String cursor, Optional<UUID> memberId) {
        return new VisitReviewQuery(ReviewQueryScope.REGION, regionCode, null, limit, cursor, memberId);
    }

    public static VisitReviewQuery place(String placeId, int limit, String cursor, Optional<UUID> memberId) {
        return new VisitReviewQuery(ReviewQueryScope.PLACE, null, placeId, limit, cursor, memberId);
    }
}

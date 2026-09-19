package com.yrootlab.onmaru.community.command.review;

public record VisitReviewPlace(
        String placeId,
        String placeName,
        String regionCode,
        double lat,
        double lng) {
}

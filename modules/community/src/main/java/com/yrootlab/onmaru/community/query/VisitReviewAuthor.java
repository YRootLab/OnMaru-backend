package com.yrootlab.onmaru.community.query;

public record VisitReviewAuthor(String displayName, String characterId, String backgroundId) {

    private static final VisitReviewAuthor FALLBACK =
            new VisitReviewAuthor("탈퇴한 여행자", "CHARACTER_01", "BACKGROUND_01");

    public VisitReviewAuthor {
        if (displayName == null || characterId == null || backgroundId == null) {
            throw new IllegalArgumentException("author profile values must not be null");
        }
    }

    public static VisitReviewAuthor fallback() {
        return FALLBACK;
    }
}

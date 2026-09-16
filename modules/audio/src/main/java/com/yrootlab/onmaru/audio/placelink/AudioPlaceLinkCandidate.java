package com.yrootlab.onmaru.audio.placelink;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record AudioPlaceLinkCandidate(
        String spotId,
        String placeId,
        AudioPlaceLinkMatchMethod matchMethod,
        BigDecimal confidence,
        AudioPlaceLinkReviewStatus reviewStatus,
        Instant reviewedAt) {

    public AudioPlaceLinkCandidate {
        Objects.requireNonNull(spotId, "spotId");
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(matchMethod, "matchMethod");
        Objects.requireNonNull(reviewStatus, "reviewStatus");
        if ((reviewStatus == AudioPlaceLinkReviewStatus.PENDING) != (reviewedAt == null)) {
            throw new IllegalArgumentException("pending candidates must be unreviewed");
        }
    }

    static AudioPlaceLinkCandidate pending(AudioPlaceLinkCandidateCommand command) {
        return new AudioPlaceLinkCandidate(
                command.spotId(),
                command.placeId(),
                command.matchMethod(),
                command.confidence(),
                AudioPlaceLinkReviewStatus.PENDING,
                null);
    }

    AudioPlaceLinkCandidate reviewed(AudioPlaceLinkReviewStatus status, Instant reviewedAt) {
        return new AudioPlaceLinkCandidate(
                spotId,
                placeId,
                matchMethod,
                confidence,
                status,
                reviewedAt);
    }
}

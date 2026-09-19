package com.yrootlab.onmaru.audio.placelink;

import java.math.BigDecimal;
import java.util.Objects;

public record AudioPlaceLinkCandidateCommand(
        String spotId,
        String placeId,
        AudioPlaceLinkMatchMethod matchMethod,
        BigDecimal confidence) {

    public AudioPlaceLinkCandidateCommand {
        if (spotId == null || spotId.isBlank()) {
            throw new IllegalArgumentException("spotId must not be blank");
        }
        if (placeId == null || placeId.isBlank()) {
            throw new IllegalArgumentException("placeId must not be blank");
        }
        Objects.requireNonNull(matchMethod, "matchMethod");
        if (confidence != null
                && (confidence.compareTo(BigDecimal.ZERO) < 0
                || confidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("confidence must be between zero and one");
        }
    }
}

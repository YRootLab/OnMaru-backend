package com.yrootlab.onmaru.catalog.application.query.quality;

import java.time.Instant;
import java.util.Objects;

public record PublicSourceStateItem(
        String sourceId,
        String sourceRevision,
        String publicationState,
        String licenseReviewState,
        long revocationSequence,
        Instant effectiveAt,
        String reasonCode
) {
    public PublicSourceStateItem {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(sourceRevision, "sourceRevision must not be null");
        Objects.requireNonNull(publicationState, "publicationState must not be null");
        Objects.requireNonNull(licenseReviewState, "licenseReviewState must not be null");
    }

    public static PublicSourceStateItem of(
            String sourceId,
            String sourceRevision,
            String publicationState,
            String licenseReviewState,
            long revocationSequence,
            Instant effectiveAt,
            String reasonCode
    ) {
        return new PublicSourceStateItem(
                sourceId,
                sourceRevision,
                publicationState,
                licenseReviewState,
                revocationSequence,
                effectiveAt,
                reasonCode
        );
    }
}

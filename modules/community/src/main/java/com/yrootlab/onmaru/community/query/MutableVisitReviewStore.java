package com.yrootlab.onmaru.community.query;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Write-capable boundary for VisitReview lifecycle, likes, and moderation state changes. */
public interface MutableVisitReviewStore extends VisitReviewStore {

    void add(VisitReviewProjection review);

    void remove(UUID reviewId);

    void replace(VisitReviewProjection review);

    Optional<VisitReviewProjection> update(
            UUID reviewId,
            Function<VisitReviewProjection, VisitReviewProjection> updater);
}

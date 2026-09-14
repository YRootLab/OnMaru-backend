package com.yrootlab.onmaru.community.query;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryVisitReviewStore implements VisitReviewStore {

    private final List<VisitReviewProjection> reviews = new CopyOnWriteArrayList<>();
    private volatile boolean unavailable;

    @Override
    public List<VisitReviewProjection> findSnapshot() {
        if (unavailable) {
            throw new VisitReviewUnavailableException();
        }
        return List.copyOf(reviews);
    }

    public void add(VisitReviewProjection review) {
        reviews.add(review);
    }

    public void remove(UUID reviewId) {
        reviews.removeIf(review -> review.id().equals(reviewId));
    }

    public void replace(VisitReviewProjection review) {
        remove(review.id());
        add(review);
    }

    public void clear() {
        reviews.clear();
        unavailable = false;
    }

    public void markUnavailable() {
        unavailable = true;
    }
}

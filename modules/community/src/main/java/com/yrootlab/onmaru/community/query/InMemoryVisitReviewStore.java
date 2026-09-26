package com.yrootlab.onmaru.community.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public final class InMemoryVisitReviewStore implements MutableVisitReviewStore {

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

    public synchronized Optional<VisitReviewProjection> update(
            UUID reviewId,
            Function<VisitReviewProjection, VisitReviewProjection> updater) {
        for (int index = 0; index < reviews.size(); index++) {
            var current = reviews.get(index);
            if (current.id().equals(reviewId)) {
                var updated = updater.apply(current);
                reviews.set(index, updated);
                return Optional.of(updated);
            }
        }
        return Optional.empty();
    }

    public void clear() {
        reviews.clear();
        unavailable = false;
    }

    public void markUnavailable() {
        unavailable = true;
    }
}

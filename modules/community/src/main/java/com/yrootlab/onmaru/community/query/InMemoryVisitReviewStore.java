package com.yrootlab.onmaru.community.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.catalog.application.pagination.AdminPage;
import java.util.Comparator;

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

    @Override
    public AdminPage<VisitReviewProjection> findAdminPage(VisitReviewStatus status, String query, int limit, AdminCursor cursor) {
        if (unavailable) throw new VisitReviewUnavailableException();
        String search = query == null ? null : query.toLowerCase(java.util.Locale.ROOT);
        var items = reviews.stream()
                .filter(review -> status == null || review.status() == status)
                .filter(review -> search == null || review.text().toLowerCase(java.util.Locale.ROOT).contains(search)
                        || review.placeName() != null && review.placeName().toLowerCase(java.util.Locale.ROOT).contains(search))
                .sorted(Comparator.comparing(VisitReviewProjection::createdAt).reversed()
                        .thenComparing(VisitReviewProjection::id, Comparator.reverseOrder()))
                .filter(review -> cursor == null || review.createdAt().isBefore(cursor.timestamp())
                        || review.createdAt().equals(cursor.timestamp()) && review.id().compareTo(cursor.id()) < 0)
                .limit(limit + 1L)
                .toList();
        boolean hasNext = items.size() > limit;
        return new AdminPage<>(items.subList(0, Math.min(limit, items.size())), hasNext);
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

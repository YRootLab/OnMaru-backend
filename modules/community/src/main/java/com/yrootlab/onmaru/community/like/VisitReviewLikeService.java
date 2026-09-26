package com.yrootlab.onmaru.community.like;

import com.yrootlab.onmaru.community.query.MutableVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;

import java.util.LinkedHashSet;
import java.util.UUID;

public final class VisitReviewLikeService {

    private final MutableVisitReviewStore store;

    public VisitReviewLikeService(MutableVisitReviewStore store) {
        this.store = store;
    }

    public LikeState like(UUID memberId, UUID reviewId) {
        return update(memberId, reviewId, true);
    }

    public LikeState unlike(UUID memberId, UUID reviewId) {
        return update(memberId, reviewId, false);
    }

    private LikeState update(UUID memberId, UUID reviewId, boolean desiredLiked) {
        return store.update(reviewId, review -> updateReview(memberId, review, desiredLiked))
                .map(review -> new LikeState(review.likedMemberIds().contains(memberId), review.likedMemberIds().size()))
                .orElseThrow(VisitReviewLikeNotFoundException::new);
    }

    private VisitReviewProjection updateReview(UUID memberId, VisitReviewProjection review, boolean desiredLiked) {
        if (review.status() != VisitReviewStatus.PUBLISHED) {
            throw new VisitReviewLikeNotFoundException();
        }
        if (review.authorMemberId().equals(memberId)) {
            throw new SelfVisitReviewLikeException();
        }
        var likedMemberIds = new LinkedHashSet<>(review.likedMemberIds());
        if (desiredLiked) {
            likedMemberIds.add(memberId);
        } else {
            likedMemberIds.remove(memberId);
        }
        return new VisitReviewProjection(
                review.id(),
                review.placeId(),
                review.placeName(),
                review.regionCode(),
                review.lat(),
                review.lng(),
                review.text(),
                review.mood(),
                review.score(),
                review.tags(),
                review.createdAt(),
                review.authorMemberId(),
                likedMemberIds,
                review.status());
    }
}

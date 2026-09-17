package com.yrootlab.onmaru.web.me.timeline;

import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.community.query.VisitReviewStore;
import com.yrootlab.onmaru.journey.timeline.TimelineVisitReviewRecord;
import com.yrootlab.onmaru.journey.timeline.TimelineVisitReviewSource;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class VisitReviewTimelineAdapter implements TimelineVisitReviewSource {

    private final VisitReviewStore reviewStore;

    VisitReviewTimelineAdapter(VisitReviewStore reviewStore) {
        this.reviewStore = Objects.requireNonNull(reviewStore, "reviewStore");
    }

    @Override
    public List<TimelineVisitReviewRecord> records(UUID memberId) {
        return reviewStore.findSnapshot().stream()
                .filter(review -> review.authorMemberId().equals(memberId))
                .map(review -> new TimelineVisitReviewRecord(
                        review.id().toString(),
                        review.placeId(),
                        review.placeName(),
                        review.createdAt(),
                        review.status() == VisitReviewStatus.PUBLISHED))
                .toList();
    }
}

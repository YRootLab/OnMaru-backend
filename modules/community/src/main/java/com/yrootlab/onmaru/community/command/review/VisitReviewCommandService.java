package com.yrootlab.onmaru.community.command.review;

import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReview;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;

import java.text.Normalizer;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

public final class VisitReviewCommandService {

    private final InMemoryVisitReviewStore store;
    private final VisitReviewPlaceLookup placeLookup;
    private final ReviewIdGenerator reviewIdGenerator;
    private final Clock clock;

    public VisitReviewCommandService(
            InMemoryVisitReviewStore store,
            VisitReviewPlaceLookup placeLookup,
            ReviewIdGenerator reviewIdGenerator,
            Clock clock) {
        this.store = store;
        this.placeLookup = placeLookup;
        this.reviewIdGenerator = reviewIdGenerator;
        this.clock = clock;
    }

    public VisitReview create(UUID memberId, String placeId, CreateVisitReviewCommand command) {
        var place = placeLookup.findEligiblePlace(placeId)
                .orElseThrow(VisitReviewPlaceNotEligibleException::new);
        var text = normalizeText(command.text());
        var projection = new VisitReviewProjection(
                reviewIdGenerator.generate(),
                place.placeId(),
                place.placeName(),
                place.regionCode(),
                place.lat(),
                place.lng(),
                text,
                clock.instant(),
                memberId,
                Set.of(),
                VisitReviewStatus.PUBLISHED);
        store.add(projection);
        return toReview(projection, memberId);
    }

    public void delete(UUID memberId, UUID reviewId) {
        var review = store.findSnapshot().stream()
                .filter(candidate -> candidate.id().equals(reviewId))
                .filter(candidate -> candidate.status() == VisitReviewStatus.PUBLISHED)
                .filter(candidate -> candidate.authorMemberId().equals(memberId))
                .findFirst()
                .orElseThrow(VisitReviewNotFoundException::new);
        store.replace(new VisitReviewProjection(
                review.id(),
                review.placeId(),
                review.placeName(),
                review.regionCode(),
                review.lat(),
                review.lng(),
                review.text(),
                review.createdAt(),
                review.authorMemberId(),
                review.likedMemberIds(),
                VisitReviewStatus.REMOVED));
    }

    private VisitReview toReview(VisitReviewProjection projection, UUID memberId) {
        return new VisitReview(
                projection.id().toString(),
                projection.placeId(),
                projection.placeName(),
                projection.lat(),
                projection.lng(),
                projection.text(),
                projection.createdAt(),
                projection.authorMemberId().equals(memberId),
                projection.likedMemberIds().size(),
                projection.likedMemberIds().contains(memberId));
    }

    private String normalizeText(String rawText) {
        if (rawText == null) {
            throw new VisitReviewTextInvalidException("text is required");
        }
        var text = Normalizer.normalize(rawText.replace("\r\n", "\n").replace('\r', '\n').trim(), Normalizer.Form.NFC);
        if (text.isBlank()) {
            throw new VisitReviewTextInvalidException("text must not be blank");
        }
        if (text.codePointCount(0, text.length()) > 300) {
            throw new VisitReviewTextInvalidException("text must be 300 code points or less");
        }
        if (text.lines().count() > 5) {
            throw new VisitReviewTextInvalidException("text must be five lines or less");
        }
        return text;
    }
}

package com.yrootlab.onmaru.community.query;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class VisitReviewQueryService {

    private static final String SCHEMA_VERSION = "1.2";
    private static final String CURSOR_PREFIX = "m2.visit-reviews.cursor.";
    private static final Duration CURSOR_TTL = Duration.ofMinutes(10);

    private final VisitReviewStore store;
    private final Clock clock;

    public VisitReviewQueryService(VisitReviewStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public ReviewPage list(VisitReviewQuery query) {
        validate(query);
        var queryKey = queryKey(query);
        var cursor = decodeCursor(query.cursor(), queryKey, query.limit());
        var filtered = store.findSnapshot().stream()
                .filter(review -> review.status() == VisitReviewStatus.PUBLISHED)
                .filter(review -> matchesQuery(query, review))
                .sorted(order())
                .filter(review -> cursor == null || isAfterCursor(review, cursor))
                .toList();
        var limited = filtered.stream().limit(query.limit() + 1L).toList();
        boolean hasMore = limited.size() > query.limit();
        List<VisitReviewProjection> pageItems = hasMore ? limited.subList(0, query.limit()) : limited;
        var items = pageItems.stream()
                .map(review -> new VisitReview(
                        review.id().toString(),
                        review.placeId(),
                        review.placeName(),
                        review.lat(),
                        review.lng(),
                        review.text(),
                        review.createdAt(),
                        query.memberId().map(review.authorMemberId()::equals).orElse(false),
                        review.likedMemberIds().size(),
                        query.memberId().map(review.likedMemberIds()::contains).orElse(false)))
                .toList();
        var nextCursor = hasMore ? encodeCursor(queryKey, query.limit(), pageItems.getLast()) : null;
        return new ReviewPage(
                SCHEMA_VERSION,
                queryKey,
                items,
                nextCursor,
                hasMore,
                clock.instant(),
                new ReviewCoverage(ReviewCoverageStatus.SUPPORTED, coverageRegions(filtered)));
    }

    private void validate(VisitReviewQuery query) {
        if (query.scope() == null || query.scope() == ReviewQueryScope.NEARBY || query.scope() == ReviewQueryScope.VIEWPORT) {
            throw new VisitReviewInvalidRequestException("scope");
        }
        if (query.limit() < 1 || query.limit() > 50) {
            throw new VisitReviewInvalidRequestException("limit");
        }
        if (query.scope() == ReviewQueryScope.ALL && query.regionCode() != null) {
            throw new VisitReviewInvalidRequestException("regionCode");
        }
        if (query.scope() == ReviewQueryScope.REGION && isBlank(query.regionCode())) {
            throw new VisitReviewInvalidRequestException("regionCode");
        }
        if (query.scope() == ReviewQueryScope.PLACE && isBlank(query.placeId())) {
            throw new VisitReviewInvalidRequestException("placeId");
        }
    }

    private boolean matchesQuery(VisitReviewQuery query, VisitReviewProjection review) {
        return switch (query.scope()) {
            case ALL -> true;
            case REGION -> query.regionCode().equals(review.regionCode());
            case PLACE -> query.placeId().equals(review.placeId());
            case NEARBY, VIEWPORT -> false;
        };
    }

    private Comparator<VisitReviewProjection> order() {
        return Comparator.comparing(VisitReviewProjection::createdAt).reversed()
                .thenComparing(VisitReviewProjection::id, Comparator.reverseOrder());
    }

    private boolean isAfterCursor(VisitReviewProjection review, DecodedCursor cursor) {
        int createdComparison = review.createdAt().compareTo(cursor.createdAt());
        if (createdComparison < 0) {
            return true;
        }
        return createdComparison == 0 && review.id().compareTo(cursor.reviewId()) < 0;
    }

    private String encodeCursor(String queryKey, int limit, VisitReviewProjection review) {
        var payload = String.join(
                "\n",
                queryKey,
                Integer.toString(limit),
                review.createdAt().toString(),
                review.id().toString(),
                clock.instant().toString());
        return CURSOR_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private DecodedCursor decodeCursor(String cursor, String queryKey, int limit) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        if (!cursor.startsWith(CURSOR_PREFIX)) {
            throw new VisitReviewCursorInvalidException();
        }
        try {
            var payload = new String(Base64.getUrlDecoder().decode(cursor.substring(CURSOR_PREFIX.length())), StandardCharsets.UTF_8);
            var parts = payload.split("\n", -1);
            if (parts.length != 5 || !parts[0].equals(queryKey) || Integer.parseInt(parts[1]) != limit) {
                throw new VisitReviewCursorInvalidException();
            }
            var createdAt = Instant.parse(parts[2]);
            var issuedAt = Instant.parse(parts[4]);
            if (issuedAt.plus(CURSOR_TTL).isBefore(clock.instant())) {
                throw new VisitReviewCursorExpiredException();
            }
            return new DecodedCursor(createdAt, UUID.fromString(parts[3]));
        } catch (VisitReviewCursorInvalidException | VisitReviewCursorExpiredException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VisitReviewCursorInvalidException();
        }
    }

    private String queryKey(VisitReviewQuery query) {
        return switch (query.scope()) {
            case ALL -> "ALL";
            case REGION -> "REGION:" + query.regionCode();
            case PLACE -> "PLACE:" + query.placeId();
            case NEARBY, VIEWPORT -> throw new VisitReviewInvalidRequestException("scope");
        };
    }

    private List<String> coverageRegions(List<VisitReviewProjection> reviews) {
        return reviews.stream()
                .map(VisitReviewProjection::regionCode)
                .distinct()
                .sorted()
                .toList();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record DecodedCursor(Instant createdAt, UUID reviewId) {
    }
}

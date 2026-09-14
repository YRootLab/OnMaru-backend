package com.yrootlab.onmaru.community.like;

import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewQuery;
import com.yrootlab.onmaru.community.query.VisitReviewQueryService;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisitReviewLikeServiceTests {

    private static final UUID REVIEW_ID = UUID.fromString("00000000-0000-0000-0000-000000000122");
    private static final UUID AUTHOR_ID = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");
    private static final UUID MEMBER_ID = UUID.fromString("8ce13b1d-01bb-42ea-8457-5e0df299a5de");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void concurrentLikeCreatesOneLikeAndAccurateCount() throws Exception {
        var store = new InMemoryVisitReviewStore();
        store.add(review(VisitReviewStatus.PUBLISHED, Set.of()));
        var service = new VisitReviewLikeService(store);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var results = new ArrayList<LikeState>();

        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 8; index++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    synchronized (results) {
                        results.add(service.like(MEMBER_ID, REVIEW_ID));
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }

        assertThat(results).hasSize(8);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.likedByMe()).isTrue();
            assertThat(result.likeCount()).isEqualTo(1);
        });
        var page = new VisitReviewQueryService(store, CLOCK)
                .list(VisitReviewQuery.all(20, null, java.util.Optional.of(MEMBER_ID)));
        assertThat(page.items().getFirst().likedByMe()).isTrue();
        assertThat(page.items().getFirst().likeCount()).isEqualTo(1);
    }

    @Test
    void unlikeIsDesiredStateAndKeepsAccurateCount() {
        var store = new InMemoryVisitReviewStore();
        store.add(review(VisitReviewStatus.PUBLISHED, Set.of(MEMBER_ID)));
        var service = new VisitReviewLikeService(store);

        LikeState first = service.unlike(MEMBER_ID, REVIEW_ID);
        LikeState second = service.unlike(MEMBER_ID, REVIEW_ID);

        assertThat(first.likedByMe()).isFalse();
        assertThat(first.likeCount()).isZero();
        assertThat(second.likedByMe()).isFalse();
        assertThat(second.likeCount()).isZero();
    }

    @Test
    void forbidsSelfLikeAndHidesHiddenOrDeletedReviews() {
        var store = new InMemoryVisitReviewStore();
        store.add(review(VisitReviewStatus.PUBLISHED, Set.of()));
        store.add(review(UUID.fromString("00000000-0000-0000-0000-000000000123"), VisitReviewStatus.HIDDEN, Set.of()));
        var service = new VisitReviewLikeService(store);

        assertThatThrownBy(() -> service.like(AUTHOR_ID, REVIEW_ID))
                .isInstanceOf(SelfVisitReviewLikeException.class);
        assertThatThrownBy(() -> service.like(MEMBER_ID, UUID.fromString("00000000-0000-0000-0000-000000000123")))
                .isInstanceOf(VisitReviewLikeNotFoundException.class);
        assertThatThrownBy(() -> service.unlike(MEMBER_ID, UUID.fromString("00000000-0000-0000-0000-000000000124")))
                .isInstanceOf(VisitReviewLikeNotFoundException.class);
    }

    private VisitReviewProjection review(VisitReviewStatus status, Set<UUID> likedBy) {
        return review(REVIEW_ID, status, likedBy);
    }

    private VisitReviewProjection review(UUID reviewId, VisitReviewStatus status, Set<UUID> likedBy) {
        return new VisitReviewProjection(
                reviewId,
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                "kr-45-jeonju",
                35.8151,
                127.1530,
                "비 오는 날 처마 밑에서 쉬기 좋았습니다.",
                Instant.parse("2026-09-15T02:00:00Z"),
                AUTHOR_ID,
                likedBy,
                status);
    }
}

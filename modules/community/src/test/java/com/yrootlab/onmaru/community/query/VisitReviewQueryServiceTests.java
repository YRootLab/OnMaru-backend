package com.yrootlab.onmaru.community.query;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisitReviewQueryServiceTests {

    private static final UUID MEMBER_ID = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");
    private static final UUID OTHER_MEMBER_ID = UUID.fromString("8ce13b1d-01bb-42ea-8457-5e0df299a5de");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void listsAllPublishedReviewsByCreatedAtAndIdDescending() {
        var store = new InMemoryVisitReviewStore();
        var older = review("00000000-0000-0000-0000-000000000001", "p-jeonju-hanok-village",
                "kr-45-jeonju", Instant.parse("2026-09-15T01:00:00Z"), OTHER_MEMBER_ID);
        var lowerId = review("00000000-0000-0000-0000-000000000002", "p-jeonju-hanok-village",
                "kr-45-jeonju", Instant.parse("2026-09-15T02:00:00Z"), OTHER_MEMBER_ID);
        var higherIdMine = review("00000000-0000-0000-0000-000000000003", "p-bukchon-hanok-cafe",
                "kr-11-jongno", Instant.parse("2026-09-15T02:00:00Z"), MEMBER_ID, MEMBER_ID);
        store.add(older);
        store.add(lowerId);
        store.add(higherIdMine);
        store.add(review("00000000-0000-0000-0000-000000000004", "p-hidden",
                "kr-45-jeonju", Instant.parse("2026-09-15T03:00:00Z"), OTHER_MEMBER_ID).hidden());
        var service = new VisitReviewQueryService(store, CLOCK);

        ReviewPage page = service.list(VisitReviewQuery.all(2, null, java.util.Optional.of(MEMBER_ID)));

        assertThat(page.queryKey()).isEqualTo("ALL");
        assertThat(page.items()).extracting(VisitReview::id)
                .containsExactly(
                        "00000000-0000-0000-0000-000000000003",
                        "00000000-0000-0000-0000-000000000002");
        assertThat(page.items().getFirst().mine()).isTrue();
        assertThat(page.items().getFirst().likedByMe()).isTrue();
        assertThat(page.items().getFirst().likeCount()).isEqualTo(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(page.coverage().status()).isEqualTo(ReviewCoverageStatus.SUPPORTED);
        assertThat(page.coverage().regionCodes()).containsExactly("kr-11-jongno", "kr-45-jeonju");
    }

    @Test
    void cursorUsesReturnedLastItemAndKeepsScopeRegionAndLimitBound() {
        var store = new InMemoryVisitReviewStore();
        store.add(review("00000000-0000-0000-0000-000000000003", "p-jeonju-hanok-village",
                "kr-45-jeonju", Instant.parse("2026-09-15T03:00:00Z"), OTHER_MEMBER_ID));
        store.add(review("00000000-0000-0000-0000-000000000002", "p-jeonju-hanok-village",
                "kr-45-jeonju", Instant.parse("2026-09-15T02:00:00Z"), OTHER_MEMBER_ID));
        store.add(review("00000000-0000-0000-0000-000000000001", "p-jeonju-hanok-village",
                "kr-45-jeonju", Instant.parse("2026-09-15T01:00:00Z"), OTHER_MEMBER_ID));
        var service = new VisitReviewQueryService(store, CLOCK);

        ReviewPage first = service.list(VisitReviewQuery.region("kr-45-jeonju", 2, null, java.util.Optional.empty()));
        store.remove(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        ReviewPage second = service.list(VisitReviewQuery.region("kr-45-jeonju", 2, first.nextCursor(), java.util.Optional.empty()));

        assertThat(first.items()).extracting(VisitReview::id)
                .containsExactly(
                        "00000000-0000-0000-0000-000000000003",
                        "00000000-0000-0000-0000-000000000002");
        assertThat(second.items()).extracting(VisitReview::id)
                .containsExactly("00000000-0000-0000-0000-000000000001");
        assertThatThrownBy(() -> service.list(VisitReviewQuery.all(2, first.nextCursor(), java.util.Optional.empty())))
                .isInstanceOf(VisitReviewCursorInvalidException.class);
        assertThatThrownBy(() -> service.list(VisitReviewQuery.region("kr-45-jeonju", 1, first.nextCursor(), java.util.Optional.empty())))
                .isInstanceOf(VisitReviewCursorInvalidException.class);
    }

    @Test
    void filtersByRegionAndPlace() {
        var store = new InMemoryVisitReviewStore();
        store.add(review("00000000-0000-0000-0000-000000000001", "p-jeonju-hanok-village",
                "kr-45-jeonju", Instant.parse("2026-09-15T01:00:00Z"), OTHER_MEMBER_ID));
        store.add(review("00000000-0000-0000-0000-000000000002", "p-bukchon-hanok-cafe",
                "kr-11-jongno", Instant.parse("2026-09-15T02:00:00Z"), OTHER_MEMBER_ID));
        var service = new VisitReviewQueryService(store, CLOCK);

        ReviewPage region = service.list(VisitReviewQuery.region("kr-45-jeonju", 20, null, java.util.Optional.empty()));
        ReviewPage place = service.list(VisitReviewQuery.place("p-bukchon-hanok-cafe", 20, null, java.util.Optional.empty()));

        assertThat(region.items()).extracting(VisitReview::placeId)
                .containsExactly("p-jeonju-hanok-village");
        assertThat(place.queryKey()).isEqualTo("PLACE:p-bukchon-hanok-cafe");
        assertThat(place.items()).extracting(VisitReview::placeId)
                .containsExactly("p-bukchon-hanok-cafe");
    }

    @Test
    void rejectsUnsupportedScopeCombinationsMalformedCursorAndUnavailableStore() {
        var store = new InMemoryVisitReviewStore();
        var service = new VisitReviewQueryService(store, CLOCK);

        assertThatThrownBy(() -> service.list(new VisitReviewQuery(
                ReviewQueryScope.ALL, "kr-45-jeonju", null, 20, null, java.util.Optional.empty())))
                .isInstanceOf(VisitReviewInvalidRequestException.class);
        assertThatThrownBy(() -> service.list(new VisitReviewQuery(
                ReviewQueryScope.REGION, null, null, 20, null, java.util.Optional.empty())))
                .isInstanceOf(VisitReviewInvalidRequestException.class);
        assertThatThrownBy(() -> service.list(new VisitReviewQuery(
                ReviewQueryScope.NEARBY, null, null, 20, null, java.util.Optional.empty())))
                .isInstanceOf(VisitReviewInvalidRequestException.class);
        assertThatThrownBy(() -> service.list(VisitReviewQuery.all(20, "tampered", java.util.Optional.empty())))
                .isInstanceOf(VisitReviewCursorInvalidException.class);

        store.markUnavailable();
        assertThatThrownBy(() -> service.list(VisitReviewQuery.all(20, null, java.util.Optional.empty())))
                .isInstanceOf(VisitReviewUnavailableException.class);
    }

    private VisitReviewProjection review(
            String reviewId,
            String placeId,
            String regionCode,
            Instant createdAt,
            UUID authorId,
            UUID... likedBy) {
        return new VisitReviewProjection(
                UUID.fromString(reviewId),
                placeId,
                placeId.equals("p-jeonju-hanok-village") ? "전주 한옥마을" : "북촌 한옥 찻집",
                regionCode,
                placeId.equals("p-jeonju-hanok-village") ? 35.8151 : 37.5824,
                placeId.equals("p-jeonju-hanok-village") ? 127.1530 : 126.9836,
                "비 오는 날 처마 밑에서 쉬기 좋았습니다.",
                createdAt,
                authorId,
                Set.of(likedBy),
                VisitReviewStatus.PUBLISHED);
    }
}

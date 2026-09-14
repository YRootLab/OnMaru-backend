package com.yrootlab.onmaru.community.command.review;

import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewQuery;
import com.yrootlab.onmaru.community.query.VisitReviewQueryService;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisitReviewCommandServiceTests {

    private static final UUID MEMBER_ID = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");
    private static final UUID OTHER_MEMBER_ID = UUID.fromString("8ce13b1d-01bb-42ea-8457-5e0df299a5de");
    private static final UUID REVIEW_ID = UUID.fromString("00000000-0000-0000-0000-000000000118");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void createsNormalizedPublishedReviewForEligiblePlace() {
        var store = new InMemoryVisitReviewStore();
        var service = service(store);

        var review = service.create(MEMBER_ID, "p-jeonju-hanok-village",
                new CreateVisitReviewCommand("  전주\r\n처마가 좋았습니다.  "));

        assertThat(review.id()).isEqualTo(REVIEW_ID.toString());
        assertThat(review.placeId()).isEqualTo("p-jeonju-hanok-village");
        assertThat(review.text()).isEqualTo(Normalizer.normalize("전주\n처마가 좋았습니다.", Normalizer.Form.NFC));
        assertThat(review.mine()).isTrue();
        var queryPage = new VisitReviewQueryService(store, CLOCK)
                .list(VisitReviewQuery.place("p-jeonju-hanok-village", 20, null, Optional.of(MEMBER_ID)));
        assertThat(queryPage.items()).extracting(com.yrootlab.onmaru.community.query.VisitReview::id)
                .containsExactly(REVIEW_ID.toString());
    }

    @Test
    void rejectsBlankTooLongTooManyLinesAndIneligiblePlace() {
        var service = service(new InMemoryVisitReviewStore());

        assertThatThrownBy(() -> service.create(MEMBER_ID, "p-jeonju-hanok-village", new CreateVisitReviewCommand("   ")))
                .isInstanceOf(VisitReviewTextInvalidException.class);
        assertThatThrownBy(() -> service.create(MEMBER_ID, "p-jeonju-hanok-village", new CreateVisitReviewCommand("가".repeat(301))))
                .isInstanceOf(VisitReviewTextInvalidException.class);
        assertThatThrownBy(() -> service.create(MEMBER_ID, "p-jeonju-hanok-village", new CreateVisitReviewCommand("1\n2\n3\n4\n5\n6")))
                .isInstanceOf(VisitReviewTextInvalidException.class);
        assertThatThrownBy(() -> service.create(MEMBER_ID, "p-private-place", new CreateVisitReviewCommand("좋았습니다.")))
                .isInstanceOf(VisitReviewPlaceNotEligibleException.class);
    }

    @Test
    void deletesOnlyOwnPublishedReview() {
        var store = new InMemoryVisitReviewStore();
        var service = service(store);
        var review = service.create(MEMBER_ID, "p-jeonju-hanok-village", new CreateVisitReviewCommand("좋았습니다."));

        assertThatThrownBy(() -> service.delete(OTHER_MEMBER_ID, UUID.fromString(review.id())))
                .isInstanceOf(VisitReviewNotFoundException.class);

        service.delete(MEMBER_ID, UUID.fromString(review.id()));
        var queryPage = new VisitReviewQueryService(store, CLOCK)
                .list(VisitReviewQuery.all(20, null, Optional.of(MEMBER_ID)));
        assertThat(queryPage.items()).isEmpty();
    }

    private VisitReviewCommandService service(InMemoryVisitReviewStore store) {
        return new VisitReviewCommandService(
                store,
                placeId -> switch (placeId) {
                    case "p-jeonju-hanok-village" -> Optional.of(new VisitReviewPlace(
                            placeId,
                            "전주 한옥마을",
                            "kr-45-jeonju",
                            35.8151,
                            127.1530));
                    default -> Optional.empty();
                },
                () -> REVIEW_ID,
                CLOCK);
    }
}

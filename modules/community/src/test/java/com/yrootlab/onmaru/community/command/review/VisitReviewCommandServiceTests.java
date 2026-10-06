package com.yrootlab.onmaru.community.command.review;

import com.yrootlab.onmaru.catalog.externalplace.ExternalPlace;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlaceCandidate;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlacePolicy;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlaceProvider;
import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewQuery;
import com.yrootlab.onmaru.community.query.VisitReviewQueryService;
import com.yrootlab.onmaru.community.query.VisitReviewAuthor;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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
        assertThat(review.author()).isEqualTo(new VisitReviewAuthor(
                "고요한 마루 0552", "CHARACTER_03", "BACKGROUND_07"));
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

    @Test
    void createsReviewFromResolvedExternalPlaceSnapshot() {
        var store = new InMemoryVisitReviewStore();
        var registryCalls = new AtomicInteger();
        var transactionCalls = new AtomicInteger();
        var service = externalService(store, registryCalls, transactionCalls);

        var review = service.createExternal(MEMBER_ID, new CreateExternalPlaceVisitReviewCommand(
                new ExternalPlaceCandidate(ExternalPlaceProvider.KAKAO, " 123456789 ", " 대청댐 ",
                        36.4952, 127.4981),
                " 경치가 좋았어요. ", "한적", 4, List.of("#힐링", "힐링", "#야경")));

        assertThat(review.placeId()).isEqualTo("p-ext-00000000000000000000000000000643");
        assertThat(review.placeName()).isEqualTo("대청댐");
        assertThat(review.lat()).isEqualTo(36.4952);
        assertThat(review.lng()).isEqualTo(127.4981);
        assertThat(review.text()).isEqualTo("경치가 좋았어요.");
        assertThat(review.tags()).containsExactly("힐링", "야경");
        assertThat(store.findSnapshot()).singleElement()
                .extracting(com.yrootlab.onmaru.community.query.VisitReviewProjection::regionCode)
                .isEqualTo("kr-unassigned");
        assertThat(registryCalls).hasValue(1);
        assertThat(transactionCalls).hasValue(1);
    }

    @Test
    void validatesContentBeforeMutatingExternalPlaceRegistry() {
        var registryCalls = new AtomicInteger();
        var transactionCalls = new AtomicInteger();
        var service = externalService(new InMemoryVisitReviewStore(), registryCalls, transactionCalls);

        assertThatThrownBy(() -> service.createExternal(MEMBER_ID, new CreateExternalPlaceVisitReviewCommand(
                new ExternalPlaceCandidate(ExternalPlaceProvider.KAKAO, "123", "장소", 37.0, 127.0),
                "좋았습니다.", null, null, List.of("####하이"))))
                .isInstanceOf(VisitReviewWarmthInvalidException.class);

        assertThat(registryCalls).hasValue(0);
        assertThat(transactionCalls).hasValue(0);
    }

    @Test
    void doesNotStoreReviewWhenExternalRegistryFails() {
        var store = new InMemoryVisitReviewStore();
        var service = new VisitReviewCommandService(
                store,
                placeId -> Optional.empty(),
                new ExternalPlacePolicy(),
                (candidate, regionCode) -> { throw new IllegalStateException("registry unavailable"); },
                candidate -> "kr-unassigned",
                directTransaction(),
                () -> REVIEW_ID,
                ignored -> java.util.Map.of(),
                new VisitReviewTagPolicy(),
                CLOCK);

        assertThatThrownBy(() -> service.createExternal(MEMBER_ID, new CreateExternalPlaceVisitReviewCommand(
                new ExternalPlaceCandidate(ExternalPlaceProvider.KAKAO, "123", "장소", 37.0, 127.0),
                "좋았습니다.", null, null, List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("registry unavailable");
        assertThat(store.findSnapshot()).isEmpty();
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
                memberIds -> java.util.Map.of(MEMBER_ID, new VisitReviewAuthor(
                        "고요한 마루 0552", "CHARACTER_03", "BACKGROUND_07")),
                CLOCK);
    }

    private VisitReviewCommandService externalService(
            InMemoryVisitReviewStore store,
            AtomicInteger registryCalls,
            AtomicInteger transactionCalls) {
        return new VisitReviewCommandService(
                store,
                placeId -> Optional.empty(),
                new ExternalPlacePolicy(),
                (candidate, regionCode) -> {
                    registryCalls.incrementAndGet();
                    return new ExternalPlace(
                            UUID.fromString("00000000-0000-0000-0000-000000000643"),
                            "p-ext-00000000000000000000000000000643",
                            candidate.name(),
                            regionCode,
                            candidate.lat(),
                            candidate.lng(),
                            true);
                },
                candidate -> "kr-unassigned",
                new VisitReviewTransaction() {
                    @Override
                    public <T> T execute(Supplier<T> operation) {
                        transactionCalls.incrementAndGet();
                        return operation.get();
                    }
                },
                () -> REVIEW_ID,
                memberIds -> java.util.Map.of(MEMBER_ID, new VisitReviewAuthor(
                        "고요한 마루 0552", "CHARACTER_03", "BACKGROUND_07")),
                new VisitReviewTagPolicy(),
                CLOCK);
    }

    private VisitReviewTransaction directTransaction() {
        return new VisitReviewTransaction() {
            @Override
            public <T> T execute(Supplier<T> operation) {
                return operation.get();
            }
        };
    }
}

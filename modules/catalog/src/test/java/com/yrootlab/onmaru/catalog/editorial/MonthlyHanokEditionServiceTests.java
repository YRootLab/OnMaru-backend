package com.yrootlab.onmaru.catalog.editorial;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListCategory;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListProjection;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStatus;
import com.yrootlab.onmaru.catalog.application.query.hanok.InMemoryHanokListStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonthlyHanokEditionServiceTests {

    private static final UUID MEMBER_ID = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");

    @Test
    void publishesOnlyPublicPlacementsAndReturnsPublishedOrder() {
        var hanoks = new InMemoryHanokListStore();
        hanoks.add(card("p-jeonju-hanok-village", "전주 한옥마을", HanokListCategory.HANOK));
        hanoks.add(card("p-bukchon-hanok-cafe", "북촌 한옥 찻집", HanokListCategory.HANOK_CAFE));
        var store = new InMemoryMonthlyHanokEditionStore();
        var service = new MonthlyHanokEditionService(store, hanoks, savedByMe("p-jeonju-hanok-village"));

        service.publish(new MonthlyHanokEditionDraft(
                YearMonth.of(2026, 9),
                "9월의 한옥 산책",
                "선선한 저녁에 걷기 좋은 한옥 장소를 모았습니다.",
                List.of(
                        new MonthlyHanokPlacementDraft(MonthlyHanokSlot.HERO, "대표 한옥 권역으로 첫 화면에서 소개합니다.", "p-jeonju-hanok-village"),
                        new MonthlyHanokPlacementDraft(MonthlyHanokSlot.CAFE, "한옥 카페를 찾는 사용자를 위한 보조 placement입니다.", "p-bukchon-hanok-cafe"))));

        var edition = service.find(YearMonth.of(2026, 9), Optional.of(MEMBER_ID));

        assertThat(edition).hasValueSatisfying(result -> {
            assertThat(result.month()).isEqualTo("2026-09");
            assertThat(result.placements()).extracting(MonthlyHanokPlacement::slot)
                    .containsExactly(MonthlyHanokSlot.HERO, MonthlyHanokSlot.CAFE);
            assertThat(result.placements().getFirst().place().savedByMe()).isTrue();
        });
    }

    @Test
    void missingMonthReturnsEmptyEditionInsteadOfError() {
        var service = new MonthlyHanokEditionService(
                new InMemoryMonthlyHanokEditionStore(),
                new InMemoryHanokListStore(),
                (memberId, placeId) -> false);

        var edition = service.find(YearMonth.of(2026, 10), Optional.empty());

        assertThat(edition).hasValueSatisfying(result -> {
            assertThat(result.month()).isEqualTo("2026-10");
            assertThat(result.placements()).isEmpty();
        });
    }

    @Test
    void publishRejectsPrivateOrMissingPlaceReferences() {
        var hanoks = new InMemoryHanokListStore();
        hanoks.add(card("p-hidden", "숨김 한옥", HanokListCategory.HANOK).hidden());
        var service = new MonthlyHanokEditionService(
                new InMemoryMonthlyHanokEditionStore(),
                hanoks,
                (memberId, placeId) -> false);

        assertThatThrownBy(() -> service.publish(new MonthlyHanokEditionDraft(
                YearMonth.of(2026, 9),
                "9월의 한옥 산책",
                "선선한 저녁에 걷기 좋은 한옥 장소를 모았습니다.",
                List.of(new MonthlyHanokPlacementDraft(MonthlyHanokSlot.HERO, "숨김 장소는 게시할 수 없습니다.", "p-hidden")))))
                .isInstanceOf(MonthlyHanokPlacementNotPublicException.class);
        assertThatThrownBy(() -> service.publish(new MonthlyHanokEditionDraft(
                YearMonth.of(2026, 9),
                "9월의 한옥 산책",
                "선선한 저녁에 걷기 좋은 한옥 장소를 모았습니다.",
                List.of(new MonthlyHanokPlacementDraft(MonthlyHanokSlot.HERO, "없는 장소는 게시할 수 없습니다.", "p-missing")))))
                .isInstanceOf(MonthlyHanokPlacementNotPublicException.class);
    }

    private HanokListProjection card(String placeId, String name, HanokListCategory category) {
        return new HanokListProjection(
                placeId,
                name,
                category,
                "kr-45-jeonju",
                placeId.contains("bukchon") ? "서울 종로구" : "전북 전주시",
                placeId.contains("bukchon") ? null : "https://cdn.onmaru.example/places/%s/cover.jpg".formatted(placeId),
                placeId.contains("bukchon")
                        ? "한옥 구조를 보존한 조용한 찻집입니다."
                        : "한옥 골목과 전통 체험을 함께 둘러볼 수 있는 대표 한옥 권역입니다.",
                placeId.contains("bukchon") ? List.of("카페", "북촌") : List.of("한옥", "체험", "산책"),
                Instant.parse("2026-09-14T08:00:00Z"),
                HanokListStatus.PUBLIC);
    }

    private com.yrootlab.onmaru.catalog.application.query.hanok.HanokSavedStateLookup savedByMe(String savedPlaceId) {
        return (memberId, placeId) -> memberId.map(id -> placeId.equals(savedPlaceId)).orElse(false);
    }
}

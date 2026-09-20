package com.yrootlab.onmaru.catalog.application.query.hanok;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HanokListQueryServiceTests {

    private static final UUID MEMBER_ID = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");

    @Test
    void filtersByNormalizedKeywordRegionCategoryAndSortsDeterministically() {
        var store = new InMemoryHanokListStore();
        store.add(card("p-bukchon-hanok-cafe", "북촌 한옥 찻집", HanokListCategory.HANOK_CAFE,
                "kr-11-jongno", "서울 종로구", Instant.parse("2026-09-14T08:00:00Z")));
        store.add(card("p-jeonju-hanok-village", "전주 한옥마을", HanokListCategory.HANOK,
                "kr-45-jeonju", "전북 전주시", Instant.parse("2026-09-14T08:00:00Z")));
        store.add(card("p-hidden", "숨김 한옥", HanokListCategory.HANOK,
                "kr-45-jeonju", "전북 전주시", Instant.parse("2026-09-14T08:10:00Z")).hidden());
        var service = new HanokListQueryService(store, savedByMe("p-jeonju-hanok-village"));

        HanokListPage page = service.list(new HanokListQuery(
                " 전주 ",
                "kr-45-jeonju",
                HanokListCategory.HANOK,
                false,
                20,
                null,
                Optional.of(MEMBER_ID)));

        assertThat(page.items()).extracting(HanokCard::placeId)
                .containsExactly("p-jeonju-hanok-village");
        assertThat(page.items().getFirst().savedByMe()).isTrue();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void hanokKeywordIncludesRelatedTraditionalCategories() {
        var store = new InMemoryHanokListStore();
        store.add(card("p-hanok-stay", "고택 숙박", HanokListCategory.HANOK_STAY,
                "kr-45-jeonju", "전북 전주시", Instant.parse("2026-09-14T08:00:00Z"),
                List.of("고택", "숙박")));
        store.add(card("p-traditional-food", "전통 음식점", HanokListCategory.TRADITIONAL_FOOD,
                "kr-45-jeonju", "전북 전주시", Instant.parse("2026-09-14T07:00:00Z"),
                List.of("한식", "전통음식")));
        store.add(card("p-modern-tour", "현대 관광지", HanokListCategory.NATURE_SITE,
                "kr-45-jeonju", "전북 전주시", Instant.parse("2026-09-14T06:00:00Z"),
                List.of("자연")));
        var service = new HanokListQueryService(store, (memberId, placeId) -> false);

        HanokListPage page = service.list(new HanokListQuery(
                "한옥",
                null,
                null,
                false,
                20,
                null,
                Optional.empty()));

        assertThat(page.items()).extracting(HanokCard::placeId)
                .containsExactly("p-hanok-stay", "p-traditional-food");
    }

    @Test
    void returnsStableCursorPageByPublishedAtAndPlaceId() {
        var store = new InMemoryHanokListStore();
        store.add(card("p-jeonju-hanok-village", "전주 한옥마을", HanokListCategory.HANOK,
                "kr-45-jeonju", "전북 전주시", Instant.parse("2026-09-14T08:00:00Z")));
        store.add(card("p-gyeongju-gyochon", "경주 교촌 한옥마을", HanokListCategory.HANOK,
                "kr-47-gyeongju", "경북 경주시", Instant.parse("2026-09-14T07:00:00Z")));
        var service = new HanokListQueryService(store, (memberId, placeId) -> false);

        HanokListPage first = service.list(HanokListQuery.firstPage(1, Optional.empty()));
        HanokListPage second = service.list(new HanokListQuery(
                null,
                null,
                null,
                false,
                1,
                first.nextCursor(),
                Optional.empty()));

        assertThat(first.items()).extracting(HanokCard::placeId)
                .containsExactly("p-jeonju-hanok-village");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isEqualTo("r1.hanoks.cursor.2026-09-14T08:00:00Z.p-jeonju-hanok-village");
        assertThat(second.items()).extracting(HanokCard::placeId)
                .containsExactly("p-gyeongju-gyochon");
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void rejectsMalformedOrExpiredCursor() {
        var service = new HanokListQueryService(new InMemoryHanokListStore(), (memberId, placeId) -> false);

        assertThatThrownBy(() -> service.list(new HanokListQuery(
                null,
                null,
                null,
                false,
                20,
                "tampered",
                Optional.empty())))
                .isInstanceOf(HanokCursorInvalidException.class);
        assertThatThrownBy(() -> service.list(new HanokListQuery(
                null,
                null,
                null,
                false,
                20,
                "r1.hanoks.cursor.2026-01-01T00:00:00Z.p-old",
                Optional.empty())))
                .isInstanceOf(HanokCursorExpiredException.class);
    }

    @Test
    void propagatesSnapshotUnavailableWithoutCallingSourceApi() {
        var store = new InMemoryHanokListStore();
        store.markUnavailable();
        var service = new HanokListQueryService(store, (memberId, placeId) -> false);

        assertThatThrownBy(() -> service.list(HanokListQuery.firstPage(20, Optional.empty())))
                .isInstanceOf(HanokListUnavailableException.class);
    }

    private HanokListProjection card(
            String placeId,
            String name,
            HanokListCategory category,
            String regionCode,
            String regionName,
            Instant publishedAt) {
        return card(placeId, name, category, regionCode, regionName, publishedAt, List.of("한옥"));
    }

    private HanokListProjection card(
            String placeId,
            String name,
            HanokListCategory category,
            String regionCode,
            String regionName,
            Instant publishedAt,
            List<String> tags) {
        return new HanokListProjection(
                placeId,
                name,
                category,
                regionCode,
                regionName,
                null,
                name + " 요약입니다.",
                tags,
                publishedAt,
                HanokListStatus.PUBLIC);
    }

    private HanokSavedStateLookup savedByMe(String savedPlaceId) {
        return (memberId, placeId) -> memberId.map(id -> placeId.equals(savedPlaceId)).orElse(false);
    }
}

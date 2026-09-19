package com.yrootlab.onmaru.catalog.application.query.spatial;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapPlaceQueryServiceTests {

    private static final UUID MEMBER_ID = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");

    @Test
    void listsPublicPlacesInRadiusByDistanceAndKeepsLongitudeLatitudeAxis() {
        var store = new InMemoryMapPlaceStore();
        store.add(place("p-jeonju-hanok-village", "전주 한옥마을", "한옥",
                "kr-45-jeonju", 35.8151, 127.1530));
        store.add(place("p-jeonju-gyodong-tea", "교동 찻집", "카페",
                "kr-45-jeonju", 35.8159, 127.1540));
        store.add(place("p-jeonju-boundary", "경계 장소", "문화",
                "kr-45-jeonju", 35.8151, 127.1620));
        store.add(place("p-jeonju-hidden", "숨김 장소", "한옥",
                "kr-45-jeonju", 35.8152, 127.1532).hidden());
        store.add(place("p-missing-coordinates", "좌표 누락", "한옥",
                "kr-45-jeonju", null, null));
        var service = new MapPlaceQueryService(store, savedByMe("p-jeonju-hanok-village"));

        MapPlacePage page = service.list(MapPlaceQuery.nearby(
                35.8151,
                127.1530,
                820,
                null,
                null,
                2,
                Optional.of(MEMBER_ID)));

        assertThat(page.coverageStatus()).isEqualTo(MapCoverageStatus.PARTIAL);
        assertThat(page.items()).extracting(MapPlaceCard::placeId)
                .containsExactly("p-jeonju-hanok-village", "p-jeonju-gyodong-tea");
        assertThat(page.items().getFirst().coordinates().lat()).isEqualTo(35.8151);
        assertThat(page.items().getFirst().coordinates().lng()).isEqualTo(127.1530);
        assertThat(page.items().getFirst().savedByMe()).isTrue();
        assertThat(page.items().get(1).savedByMe()).isFalse();
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    void filtersByRegionBboxAndCategoryWithLimit() {
        var store = new InMemoryMapPlaceStore();
        store.add(place("p-jeonju-hanok-village", "전주 한옥마을", "한옥",
                "kr-45-jeonju", 35.8151, 127.1530));
        store.add(place("p-jeonju-cafe", "전주 카페", "카페",
                "kr-45-jeonju", 35.8152, 127.1532));
        store.add(place("p-seoul-hanok", "서울 한옥", "한옥",
                "kr-11-jongno", 37.5824, 126.9836));
        var service = new MapPlaceQueryService(store, (memberId, placeId) -> false);

        MapPlacePage page = service.list(new MapPlaceQuery(
                "ko-KR",
                "kr-45-jeonju",
                new MapBoundingBox(127.1520, 35.8140, 127.1540, 35.8160),
                null,
                null,
                null,
                "한옥",
                1,
                Optional.empty()));

        assertThat(page.items()).extracting(MapPlaceCard::placeId)
                .containsExactly("p-jeonju-hanok-village");
        assertThat(page.coverageStatus()).isEqualTo(MapCoverageStatus.PARTIAL);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void returnsMissingCoverageWhenNoPublicPlacesMatch() {
        var service = new MapPlaceQueryService(new InMemoryMapPlaceStore(), (memberId, placeId) -> false);

        MapPlacePage page = service.list(MapPlaceQuery.region("kr-45-muju", "ko-KR", 20, Optional.empty()));

        assertThat(page.coverageStatus()).isEqualTo(MapCoverageStatus.MISSING);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void rejectsInvalidCoordinatesRadiusBboxAndLimit() {
        var service = new MapPlaceQueryService(new InMemoryMapPlaceStore(), (memberId, placeId) -> false);

        assertThatThrownBy(() -> service.list(MapPlaceQuery.nearby(
                95.0, 127.1530, 500, null, null, 20, Optional.empty())))
                .isInstanceOf(MapPlaceInvalidRequestException.class);
        assertThatThrownBy(() -> service.list(MapPlaceQuery.nearby(
                35.8151, 200.0, 500, null, null, 20, Optional.empty())))
                .isInstanceOf(MapPlaceInvalidRequestException.class);
        assertThatThrownBy(() -> service.list(MapPlaceQuery.nearby(
                35.8151, 127.1530, 0, null, null, 20, Optional.empty())))
                .isInstanceOf(MapPlaceInvalidRequestException.class);
        assertThatThrownBy(() -> service.list(new MapPlaceQuery(
                "ko-KR",
                null,
                new MapBoundingBox(127.1540, 35.8140, 127.1520, 35.8160),
                null,
                null,
                null,
                null,
                20,
                Optional.empty())))
                .isInstanceOf(MapPlaceInvalidRequestException.class);
        assertThatThrownBy(() -> service.list(MapPlaceQuery.region("kr-45-jeonju", "ko-KR", 0, Optional.empty())))
                .isInstanceOf(MapPlaceInvalidRequestException.class);
    }

    @Test
    void propagatesSnapshotUnavailableWithoutExternalApiFallback() {
        var store = new InMemoryMapPlaceStore();
        store.markUnavailable();
        var service = new MapPlaceQueryService(store, (memberId, placeId) -> false);

        assertThatThrownBy(() -> service.list(MapPlaceQuery.region("kr-45-jeonju", "ko-KR", 20, Optional.empty())))
                .isInstanceOf(MapPlaceUnavailableException.class);
    }

    private MapPlaceProjection place(
            String placeId,
            String name,
            String category,
            String regionCode,
            Double lat,
            Double lng) {
        return new MapPlaceProjection(
                placeId,
                name,
                category,
                new MapRegionRef(regionCode, regionName(regionCode), "CITY", parentRegionCode(regionCode)),
                lat == null || lng == null ? null : new MapCoordinates(lat, lng),
                "https://cdn.onmaru.example/places/" + placeId + "/cover.jpg",
                name + " 요약입니다.",
                List.of("odii-story-" + placeId.substring(2) + "-01"),
                new MapDataAvailability(MapCoverageStatus.COMPLETE, MapCoverageStatus.PARTIAL, MapCoverageStatus.COMPLETE),
                MapPlaceStatus.PUBLIC);
    }

    private String regionName(String regionCode) {
        return switch (regionCode) {
            case "kr-45-jeonju" -> "전북 전주시";
            case "kr-11-jongno" -> "서울 종로구";
            default -> "미분류";
        };
    }

    private String parentRegionCode(String regionCode) {
        return switch (regionCode) {
            case "kr-45-jeonju" -> "kr-45";
            case "kr-11-jongno" -> "kr-11";
            default -> null;
        };
    }

    private MapSavedStateLookup savedByMe(String savedPlaceId) {
        return (memberId, placeId) -> memberId.map(id -> placeId.equals(savedPlaceId)).orElse(false);
    }
}

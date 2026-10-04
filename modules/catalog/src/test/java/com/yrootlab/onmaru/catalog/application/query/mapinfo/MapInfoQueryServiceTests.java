package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MapInfoQueryServiceTests {

    @Test
    void acceptsItsOwnNextCursorWhenTheDistanceContainsADecimalPoint() {
        var snapshot = new MapInfoSnapshot("rev-1", Instant.parse("2026-09-29T00:00:00Z"), "PUBLISHED");
        var publication = new MapInfoProjectionPublication(
                "map_place_read_projection", "rev-1", snapshot.publishedAt(), "abc", 2, "map-category-v1");
        var firstPlace = new MapInfoPlaceItem(
                "p-first", "첫 한옥", "HANOK", List.of("HANOK"),
                new MapInfoRegionRef("kr-11", "서울"), new MapInfoPoint(37.5, 127.0), null, "summary", false);
        var secondPlace = new MapInfoPlaceItem(
                "p-second", "둘째 한옥", "HANOK", List.of("HANOK"),
                new MapInfoRegionRef("kr-45", "전북"), new MapInfoPoint(35.8, 127.1), null, "summary", false);
        var port = new PagingPort(
                new MapInfoQueryResult(snapshot, publication, 2, List.of(firstPlace),
                        new MapInfoCursorPosition("서울\u0000첫 한옥", 0.0, "p-first"), true),
                new MapInfoQueryResult(snapshot, publication, 2, List.of(secondPlace), null, false));
        var service = new MapInfoQueryService(port, "cursor-secret");
        var firstQuery = new MapInfoListQuery(
                MapInfoCategory.ALL, null, null, null, null, "ko-KR", 1, "REGION_NAME", null, null);

        var first = service.list(firstQuery);
        var second = service.list(new MapInfoListQuery(
                MapInfoCategory.ALL, null, null, first.nextCursor(), null,
                "ko-KR", 1, "REGION_NAME", null, null));

        assertThat(second.items()).extracting(MapInfoPlaceItem::placeId).containsExactly("p-second");
        assertThat(port.queries.get(1).cursor())
                .isEqualTo(new MapInfoCursorPosition("서울\u0000첫 한옥", 0.0, "p-first"));
    }

    @Test
    void expandsHanokToTheFourCanonicalCategoriesUsedByTheHanokCatalog() {
        var snapshot = new MapInfoSnapshot("rev-1", Instant.parse("2026-09-29T00:00:00Z"), "PUBLISHED");
        var publication = new MapInfoProjectionPublication(
                "map_place_read_projection", "rev-1", snapshot.publishedAt(), "abc", 0, "map-category-v1");
        var port = new CapturingPort(new MapInfoQueryResult(
                snapshot, publication, 0, List.of(), null, false));

        var response = new MapInfoQueryService(port).list(new MapInfoListQuery(
                MapInfoCategory.valueOf("HANOK"), null, null, null, null,
                "ko-KR", 30, "REGION_NAME", null, null));

        assertThat(port.query.canonicalCategories())
                .containsExactly("HANOK", "HANOK_STAY", "HANOK_CAFE", "HANOK_EXPERIENCE");
        assertThat(response.appliedCategories())
                .containsExactly("HANOK", "HANOK_STAY", "HANOK_CAFE", "HANOK_EXPERIENCE");
    }

    @Test
    void expandsCategoryAndPinsNextPageToTheResolvedSnapshot() {
        var snapshot = new MapInfoSnapshot("rev-1", Instant.parse("2026-09-29T00:00:00Z"), "PUBLISHED");
        var publication = new MapInfoProjectionPublication(
                "map_place_read_projection", "rev-1", snapshot.publishedAt(), "abc", 1, "map-category-v1");
        var place = new MapInfoPlaceItem(
                "p-abc", "고택", "SPOT", List.of("HANOK", "GOTAEK"),
                new MapInfoRegionRef("kr-45", "전북"), new MapInfoPoint(35.8, 127.1), null, "summary", false);
        var port = new CapturingPort(new MapInfoQueryResult(
                snapshot, publication, 1, List.of(place),
                new MapInfoCursorPosition("전북\u0000고택", 0, "p-abc"), true));

        var response = new MapInfoQueryService(port).list(new MapInfoListQuery(
                MapInfoCategory.SPOT, null, null, null, null, "ko-KR", 1, "REGION_NAME", null, null));

        assertThat(port.query.canonicalCategories())
                .containsExactly("HANOK", "HISTORIC_SITE", "CULTURE_ART", "HANOK_VILLAGE", "GOTAEK", "SPOT");
        assertThat(response.appliedCategories()).contains("HANOK", "GOTAEK");
        assertThat(response.snapshot().id()).isEqualTo("rev-1");
        assertThat(response.nextCursor()).isNotBlank();
    }

    private static final class CapturingPort implements MapInfoQueryPort {
        private final MapInfoQueryResult result;
        private MapInfoSqlQuery query;

        private CapturingPort(MapInfoQueryResult result) {
            this.result = result;
        }

        @Override
        public MapInfoQueryResult find(MapInfoSqlQuery query) {
            this.query = query;
            return result;
        }
    }

    private static final class PagingPort implements MapInfoQueryPort {
        private final List<MapInfoQueryResult> pages;
        private final List<MapInfoSqlQuery> queries = new ArrayList<>();

        private PagingPort(MapInfoQueryResult... pages) {
            this.pages = List.of(pages);
        }

        @Override
        public MapInfoQueryResult find(MapInfoSqlQuery query) {
            queries.add(query);
            return pages.get(queries.size() - 1);
        }
    }
}

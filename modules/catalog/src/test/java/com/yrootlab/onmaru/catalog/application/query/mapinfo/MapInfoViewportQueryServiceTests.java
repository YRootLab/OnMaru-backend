package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapInfoViewportQueryServiceTests {

    private final MapInfoViewportStore store = query -> new MapInfoViewportResponse(
            "1.0", MapInfoRenderMode.CLUSTER, "map-zoom-v1",
            new MapInfoSnapshot("snapshot", java.time.Instant.EPOCH, "PUBLISHED"), 2,
            List.of(), MapInfoCategoryMapping.applied(query.category()), "COMPLETE", query.bbox(),
            new MapInfoProjectionPublication("map_place_read_projection", "snapshot",
                    java.time.Instant.EPOCH, "0".repeat(64), 2, "v1"));
    private final MapInfoViewportQueryService service = new MapInfoViewportQueryService(store);

    @Test
    void acceptsThePublishedKakaoZoomProfileAndKeepsQueryContract() {
        var query = new MapInfoViewportQuery(
                new MapInfoBounds(126.8, 35.0, 127.2, 36.0), 8,
                MapInfoCategory.CAFE, null, null, "ko-KR", 100);

        var response = service.find(query);

        assertThat(response.profileVersion()).isEqualTo("map-zoom-v1");
        assertThat(response.servedBbox()).isEqualTo(query.bbox());
        assertThat(response.appliedCategories()).containsExactly("HANOK_CAFE", "TEA_HOUSE", "CAFE", "COFFEE_SHOP");
    }

    @Test
    void rejectsInvalidViewportInputsBeforeReachingTheStore() {
        var invalidBbox = new MapInfoViewportQuery(
                new MapInfoBounds(127.0, 36.0, 126.0, 35.0), 8,
                MapInfoCategory.ALL, null, null, "ko-KR", 100);

        assertThatThrownBy(() -> service.find(invalidBbox))
                .isInstanceOf(MapInfoViewportInvalidRequestException.class)
                .extracting("field")
                .isEqualTo("bbox");

        var invalidSnapshot = new MapInfoViewportQuery(
                new MapInfoBounds(126.8, 35.0, 127.2, 36.0), 8,
                MapInfoCategory.ALL, null, "not-a-uuid", "ko-KR", 100);

        assertThatThrownBy(() -> service.find(invalidSnapshot))
                .isInstanceOf(MapInfoViewportInvalidRequestException.class)
                .extracting("field")
                .isEqualTo("snapshotId");
    }

    @Test
    void expandsCategoryTabsToTheirCanonicalQueryValues() {
        var response = service.find(new MapInfoViewportQuery(
                new MapInfoBounds(126.8, 35.0, 127.2, 36.0), 5,
                MapInfoCategory.FOOD, null, null, "ko-KR", 100));

        assertThat(response.appliedCategories())
                .containsExactly("TRADITIONAL_FOOD", "KOREAN_RESTAURANT", "RESTAURANT", "FOOD");
    }

    @Test
    void exposesTheSameHanokUnionAsTheListEndpoint() {
        var response = service.find(new MapInfoViewportQuery(
                new MapInfoBounds(126.8, 35.0, 127.2, 36.0), 5,
                MapInfoCategory.valueOf("HANOK"), null, null, "ko-KR", 100));

        assertThat(response.appliedCategories())
                .containsExactly("HANOK", "HANOK_STAY", "HANOK_CAFE", "HANOK_EXPERIENCE");
        assertThat(MapInfoCategoryMapping.queryValues(MapInfoCategory.valueOf("HANOK")))
                .containsExactly("HANOK", "HANOK_STAY", "HANOK_CAFE", "HANOK_EXPERIENCE");
    }

    @Test
    void clipsPartiallyOverlappingBboxToSupportedMapArea() {
        var response = service.find(new MapInfoViewportQuery(
                new MapInfoBounds(119.0, 35.0, 121.0, 36.0), 8,
                MapInfoCategory.ALL, null, null, "ko-KR", 60));

        assertThat(response.servedBbox()).isEqualTo(new MapInfoBounds(120.0, 35.0, 121.0, 36.0));
    }

    @Test
    void rejectsBboxWithNoOverlapWithSupportedMapArea() {
        assertThatThrownBy(() -> service.find(new MapInfoViewportQuery(
                new MapInfoBounds(10.0, 10.0, 11.0, 11.0), 8,
                MapInfoCategory.ALL, null, null, "ko-KR", 60)))
                .isInstanceOf(MapInfoViewportInvalidRequestException.class)
                .extracting("field")
                .isEqualTo("bbox");
    }
}

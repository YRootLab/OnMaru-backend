package com.yrootlab.onmaru.web.map.place;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoPlaceItem;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoPoint;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoProjectionPublication;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoQueryResult;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoQueryService;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoRegionRef;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoSnapshot;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoQueryPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MapInfoControllerTests {

    @Test
    void rejectsMissingBlankAndAllCategories() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(
                new MapInfoController(new MapInfoQueryService((MapInfoQueryPort) query ->
                        new MapInfoQueryResult(new MapInfoSnapshot("rev-1", Instant.EPOCH, "PUBLISHED"),
                                null, 0, List.of(), null, false)))).build();
        for (var request : List.of(get("/api/v1/map/info/places"),
                get("/api/v1/map/info/places").param("category", " "),
                get("/api/v1/map/info/places").param("category", "ALL"))) {
            mvc.perform(request).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details.field").value("category"));
        }
    }

    @Test
    void returnsTheStablePlacesEnvelopeAndFixtureFields() throws Exception {
        var item = new MapInfoPlaceItem(
                "place-1", "온마루 한옥", "HANOK", List.of("HANOK"),
                new MapInfoRegionRef("11", "서울"), new MapInfoPoint(37.5, 127.0),
                "https://cdn.example/place-1.jpg", "한옥 설명", false);
        var result = new MapInfoQueryResult(
                new MapInfoSnapshot("rev-1", Instant.parse("2026-09-30T00:00:00Z"), "PUBLISHED"),
                new MapInfoProjectionPublication("map_place_read_projection", "rev-1",
                        Instant.parse("2026-09-30T00:00:00Z"), "checksum", 1, "map-category-v1"),
                1, List.of(item), null, false);
        var mvc = MockMvcBuilders.standaloneSetup(
                new MapInfoController(new MapInfoQueryService((MapInfoQueryPort) query -> result)))
                .build();

        mvc.perform(get("/api/v1/map/info/places").param("category", "HANOK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.coverage").value("COMPLETE"))
                .andExpect(jsonPath("$.appliedCategories[0]").value("HANOK"))
                .andExpect(jsonPath("$.items[0].placeId").value("place-1"))
                .andExpect(jsonPath("$.items[0].coordinates.lat").value(37.5))
                .andExpect(jsonPath("$.projection.projectionName").value("map_place_read_projection"));
    }

    @Test
    void acceptsHanokAsAPublicMapInfoCategory() throws Exception {
        var result = new MapInfoQueryResult(
                new MapInfoSnapshot("rev-1", Instant.EPOCH, "PUBLISHED"), null,
                0, List.of(), null, false);
        var mvc = MockMvcBuilders.standaloneSetup(
                new MapInfoController(new MapInfoQueryService((MapInfoQueryPort) query -> result))).build();

        mvc.perform(get("/api/v1/map/info/places").param("category", "HANOK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query.category").value("HANOK"))
                .andExpect(jsonPath("$.appliedCategories[0]").value("HANOK"))
                .andExpect(jsonPath("$.appliedCategories[1]").value("HANOK_STAY"))
                .andExpect(jsonPath("$.appliedCategories[2]").value("HANOK_CAFE"))
                .andExpect(jsonPath("$.appliedCategories[3]").value("HANOK_EXPERIENCE"));
    }

    @Test
    void returnsServiceUnavailableWhenTheCatalogReadFails() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(
                new MapInfoController(new MapInfoQueryService((MapInfoQueryPort) query -> {
                    throw new IllegalStateException("catalog unavailable");
                }))).build();

        mvc.perform(get("/api/v1/map/info/places").param("category", "HANOK"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"));
    }

    @Test
    void returnsConflictWhenTheRequestedSnapshotIsExpired() throws Exception {
        var result = new MapInfoQueryResult(
                new MapInfoSnapshot("rev-1", Instant.EPOCH, "PUBLISHED"), null,
                0, List.of(), null, false);
        var mvc = MockMvcBuilders.standaloneSetup(
                new MapInfoController(new MapInfoQueryService((MapInfoQueryPort) query -> result))).build();

        mvc.perform(get("/api/v1/map/info/places")
                        .param("category", "HANOK").param("snapshotId", "rev-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_EXPIRED"));
    }
}

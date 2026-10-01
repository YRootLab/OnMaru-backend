package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.time.Instant;
import java.util.List;

/** Empty development fallback; production always uses the JDBC projection store. */
public final class InMemoryMapInfoViewportStore implements MapInfoViewportStore {

    @Override
    public MapInfoViewportResponse find(MapInfoViewportQuery query) {
        var snapshot = new MapInfoSnapshot(
                "00000000-0000-0000-0000-000000000000", Instant.EPOCH, "PUBLISHED");
        var publication = new MapInfoProjectionPublication(
                "map_place_read_projection", snapshot.id(), Instant.EPOCH, "0".repeat(64), 0, "dev");
        return new MapInfoViewportResponse(
                "1.0", renderMode(query.zoomLevel()), "map-zoom-v1", snapshot, 0,
                List.of(), MapInfoCategoryMapping.applied(query.category()), "COMPLETE", query.bbox(), publication);
    }

    private MapInfoRenderMode renderMode(int zoom) {
        if (zoom <= 5) return MapInfoRenderMode.PLACE;
        if (zoom <= 7) return MapInfoRenderMode.CLUSTER;
        if (zoom <= 10) return MapInfoRenderMode.DISTRICT;
        return MapInfoRenderMode.REGION;
    }
}

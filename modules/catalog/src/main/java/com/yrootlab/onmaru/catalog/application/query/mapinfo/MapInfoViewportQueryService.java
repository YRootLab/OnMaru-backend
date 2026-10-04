package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.util.Objects;
import java.util.UUID;

public final class MapInfoViewportQueryService {

    private static final String PROFILE_VERSION = "map-zoom-v1";
    private static final MapInfoBounds SUPPORTED_BOUNDS = new MapInfoBounds(120.0, 30.0, 132.0, 45.0);

    private final MapInfoViewportStore store;

    public MapInfoViewportQueryService(MapInfoViewportStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public MapInfoViewportResponse find(MapInfoViewportQuery query) {
        validate(query);
        var bbox = query.bbox();
        var clipped = new MapInfoBounds(
                Math.max(bbox.west(), SUPPORTED_BOUNDS.west()),
                Math.max(bbox.south(), SUPPORTED_BOUNDS.south()),
                Math.min(bbox.east(), SUPPORTED_BOUNDS.east()),
                Math.min(bbox.north(), SUPPORTED_BOUNDS.north()));
        if (clipped.west() >= clipped.east() || clipped.south() >= clipped.north()) {
            throw new MapInfoViewportInvalidRequestException("bbox");
        }
        return store.find(new MapInfoViewportQuery(clipped, query.zoomLevel(), query.category(),
                query.regionCode(), query.snapshotId(), query.language(), query.limit()));
    }

    public static String profileVersion() {
        return PROFILE_VERSION;
    }

    private void validate(MapInfoViewportQuery query) {
        if (query == null) throw new MapInfoViewportInvalidRequestException("query");
        if (query.bbox() == null) throw new MapInfoViewportInvalidRequestException("bbox");
        var bbox = query.bbox();
        if (!Double.isFinite(bbox.west()) || !Double.isFinite(bbox.east())
                || !Double.isFinite(bbox.south()) || !Double.isFinite(bbox.north())
                || bbox.west() < -180 || bbox.east() > 180
                || bbox.south() < -90 || bbox.north() > 90
                || bbox.west() >= bbox.east() || bbox.south() >= bbox.north()) {
            throw new MapInfoViewportInvalidRequestException("bbox");
        }
        if (query.zoomLevel() < 1 || query.zoomLevel() > 14) {
            throw new MapInfoViewportInvalidRequestException("zoomLevel");
        }
        if (query.limit() < 1 || query.limit() > 1000) {
            throw new MapInfoViewportInvalidRequestException("limit");
        }
        if (query.snapshotId() != null && query.snapshotId().isBlank()) {
            throw new MapInfoViewportInvalidRequestException("snapshotId");
        }
        if (query.snapshotId() != null) {
            try {
                UUID.fromString(query.snapshotId());
            } catch (IllegalArgumentException exception) {
                throw new MapInfoViewportInvalidRequestException("snapshotId");
            }
        }
        if (query.regionCode() != null && query.regionCode().length() > 32) {
            throw new MapInfoViewportInvalidRequestException("regionCode");
        }
    }
}

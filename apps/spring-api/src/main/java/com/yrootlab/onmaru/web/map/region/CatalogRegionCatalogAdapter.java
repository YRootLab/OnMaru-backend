package com.yrootlab.onmaru.web.map.region;

import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryLevel;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryProjection;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryStore;
import com.yrootlab.onmaru.community.region.RegionCatalog;
import com.yrootlab.onmaru.community.region.RegionLevel;
import com.yrootlab.onmaru.community.region.RegionProjection;

import java.util.List;
import java.util.Optional;

final class CatalogRegionCatalogAdapter implements RegionCatalog {

    private final RegionBoundaryStore store;

    CatalogRegionCatalogAdapter(RegionBoundaryStore store) {
        this.store = store;
    }

    @Override
    public Optional<String> activeRevisionId() {
        return store.activeRevision().map(revision -> revision.revisionId());
    }

    @Override
    public List<RegionProjection> regions() {
        return store.activeRevision()
                .map(revision -> revision.boundaries().stream().map(this::map).toList())
                .orElseGet(List::of);
    }

    @Override
    public List<RegionProjection> resolve(double latitude, double longitude) {
        return store.resolve(longitude, latitude).stream()
                .map(this::map)
                .toList();
    }

    private RegionProjection map(RegionBoundaryProjection boundary) {
        return new RegionProjection(
                boundary.regionCode(),
                boundary.parentRegionCode(),
                boundary.name(),
                map(boundary.level()));
    }

    private RegionLevel map(RegionBoundaryLevel level) {
        return switch (level) {
            case SIDO -> RegionLevel.PROVINCE;
            case SIGUNGU -> RegionLevel.CITY;
        };
    }
}

package com.yrootlab.onmaru.web.review.command;

import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryLevel;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryStore;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlaceCandidate;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlaceRegionResolver;

import java.util.Comparator;

public final class CatalogExternalPlaceRegionResolver implements ExternalPlaceRegionResolver {

    private final RegionBoundaryStore store;

    public CatalogExternalPlaceRegionResolver(RegionBoundaryStore store) {
        this.store = store;
    }

    @Override
    public String resolve(ExternalPlaceCandidate candidate) {
        return store.resolve(candidate.lng(), candidate.lat()).stream()
                .max(Comparator.comparingInt(boundary -> priority(boundary.level())))
                .map(boundary -> boundary.regionCode())
                .orElse("kr-unassigned");
    }

    private int priority(RegionBoundaryLevel level) {
        return level == RegionBoundaryLevel.SIGUNGU ? 2 : 1;
    }
}

package com.yrootlab.onmaru.web.audio;

import com.yrootlab.onmaru.audio.query.OdiiProjectionMetadata;
import com.yrootlab.onmaru.audio.query.OdiiProjectionMetadataResolver;
import com.yrootlab.onmaru.audio.query.OdiiRegionRef;
import com.yrootlab.onmaru.audio.sync.OdiiSpotVersion;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryLevel;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryStore;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

final class CatalogRegionOdiiProjectionMetadataResolver implements OdiiProjectionMetadataResolver {

    private static final OdiiRegionRef COUNTRY_FALLBACK =
            new OdiiRegionRef("kr", "대한민국", "COUNTRY", null);

    private final String category;
    private final Optional<RegionBoundaryStore> regionStore;

    CatalogRegionOdiiProjectionMetadataResolver(
            String category,
            Optional<RegionBoundaryStore> regionStore
    ) {
        this.category = Objects.requireNonNull(category, "category");
        this.regionStore = Objects.requireNonNull(regionStore, "regionStore");
    }

    @Override
    public OdiiProjectionMetadata resolve(OdiiSpotVersion spot) {
        if (spot.longitude() == null || spot.latitude() == null) {
            return fallback();
        }
        return regionStore.stream()
                .flatMap(store -> store.resolve(
                        spot.longitude().doubleValue(),
                        spot.latitude().doubleValue()).stream())
                .max(Comparator.comparingInt(region -> region.level() == RegionBoundaryLevel.SIGUNGU ? 1 : 0))
                .map(region -> new OdiiProjectionMetadata(
                        category,
                        new OdiiRegionRef(
                                region.regionCode(),
                                region.name(),
                                region.level() == RegionBoundaryLevel.SIGUNGU ? "CITY" : "PROVINCE",
                                region.parentRegionCode())))
                .orElseGet(this::fallback);
    }

    private OdiiProjectionMetadata fallback() {
        return new OdiiProjectionMetadata(category, COUNTRY_FALLBACK);
    }
}

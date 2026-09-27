package com.yrootlab.onmaru.web.audio;

import com.yrootlab.onmaru.audio.query.OdiiProjectionMetadata;
import com.yrootlab.onmaru.audio.query.OdiiProjectionMetadataResolver;
import com.yrootlab.onmaru.audio.query.OdiiRegionRef;
import com.yrootlab.onmaru.audio.sync.OdiiSpotVersion;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryLevel;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryStore;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class CatalogRegionOdiiProjectionMetadataResolver implements OdiiProjectionMetadataResolver {

    private static final OdiiRegionRef COUNTRY_FALLBACK =
            new OdiiRegionRef("kr", "대한민국", "COUNTRY", null);
    private static final List<ProvinceCenter> PROVINCE_CENTERS = List.of(
            new ProvinceCenter("kr-11", "서울·경기·인천", 37.5665, 126.9780),
            new ProvinceCenter("kr-42", "강원", 37.7519, 128.8761),
            new ProvinceCenter("kr-43", "충북", 36.6424, 127.4890),
            new ProvinceCenter("kr-44", "충남·대전·세종", 36.3504, 127.3845),
            new ProvinceCenter("kr-47", "경북·대구", 35.8714, 128.6014),
            new ProvinceCenter("kr-45", "전북", 35.8242, 127.1480),
            new ProvinceCenter("kr-46", "전남·광주", 35.1595, 126.8526),
            new ProvinceCenter("kr-48", "경남·부산·울산", 35.1796, 129.0756),
            new ProvinceCenter("kr-50", "제주", 33.4996, 126.5312));

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
                .orElseGet(() -> coordinateFallback(
                        spot.longitude().doubleValue(),
                        spot.latitude().doubleValue()));
    }

    private OdiiProjectionMetadata coordinateFallback(double longitude, double latitude) {
        if (longitude < 124.0 || longitude > 132.0 || latitude < 32.0 || latitude > 39.5) {
            return fallback();
        }
        ProvinceCenter nearest = PROVINCE_CENTERS.stream()
                .min(Comparator.comparingDouble(center -> center.distanceSquared(longitude, latitude)))
                .orElseThrow();
        return new OdiiProjectionMetadata(
                category,
                new OdiiRegionRef(nearest.code(), nearest.name(), "PROVINCE", null));
    }

    private OdiiProjectionMetadata fallback() {
        return new OdiiProjectionMetadata(category, COUNTRY_FALLBACK);
    }

    private record ProvinceCenter(String code, String name, double latitude, double longitude) {
        private double distanceSquared(double targetLongitude, double targetLatitude) {
            double latitudeDelta = latitude - targetLatitude;
            double longitudeDelta = (longitude - targetLongitude)
                    * Math.cos(Math.toRadians((latitude + targetLatitude) / 2.0));
            return latitudeDelta * latitudeDelta + longitudeDelta * longitudeDelta;
        }
    }
}

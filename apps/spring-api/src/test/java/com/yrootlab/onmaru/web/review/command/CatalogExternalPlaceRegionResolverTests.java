package com.yrootlab.onmaru.web.review.command;

import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryLevel;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryProjection;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryRevision;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryStore;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlaceCandidate;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlaceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogExternalPlaceRegionResolverTests {

    private static final ExternalPlaceCandidate PLACE = new ExternalPlaceCandidate(
            ExternalPlaceProvider.KAKAO, "123", "대청댐", 36.4952, 127.4981);

    @Test
    void prefersSigunguOverSido() {
        var resolver = new CatalogExternalPlaceRegionResolver(store(List.of(
                region("kr-43", RegionBoundaryLevel.SIDO),
                region("kr-43-cheongju", RegionBoundaryLevel.SIGUNGU))));

        assertThat(resolver.resolve(PLACE)).isEqualTo("kr-43-cheongju");
    }

    @Test
    void fallsBackToSidoOrUnassigned() {
        assertThat(new CatalogExternalPlaceRegionResolver(store(List.of(
                region("kr-43", RegionBoundaryLevel.SIDO)))).resolve(PLACE)).isEqualTo("kr-43");
        assertThat(new CatalogExternalPlaceRegionResolver(store(List.of())).resolve(PLACE))
                .isEqualTo("kr-unassigned");
    }

    private RegionBoundaryProjection region(String code, RegionBoundaryLevel level) {
        return new RegionBoundaryProjection("revision", code, null, code, level, null);
    }

    private RegionBoundaryStore store(List<RegionBoundaryProjection> resolved) {
        return new RegionBoundaryStore() {
            @Override
            public void activate(RegionBoundaryRevision revision) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<RegionBoundaryRevision> activeRevision() {
                return Optional.empty();
            }

            @Override
            public List<RegionBoundaryProjection> resolve(double longitude, double latitude) {
                assertThat(longitude).isEqualTo(PLACE.lng());
                assertThat(latitude).isEqualTo(PLACE.lat());
                return resolved;
            }
        };
    }
}

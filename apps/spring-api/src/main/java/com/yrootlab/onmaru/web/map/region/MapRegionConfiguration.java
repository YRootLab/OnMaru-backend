package com.yrootlab.onmaru.web.map.region;

import com.yrootlab.onmaru.catalog.application.regionboundary.BoundaryPoint;
import com.yrootlab.onmaru.catalog.application.regionboundary.InMemoryRegionBoundaryStore;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryCandidate;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryGeometry;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryImportCommand;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryImportService;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundaryLevel;
import com.yrootlab.onmaru.catalog.application.regionboundary.RegionBoundarySourceManifest;
import com.yrootlab.onmaru.community.query.VisitReviewStore;
import com.yrootlab.onmaru.community.region.RegionCatalog;
import com.yrootlab.onmaru.community.region.VisitReviewRegionReadService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Configuration
class MapRegionConfiguration {

    @Bean
    InMemoryRegionBoundaryStore regionBoundaryStore() {
        var store = new InMemoryRegionBoundaryStore();
        var importService = new RegionBoundaryImportService(store);
        importService.importAndActivate(new RegionBoundaryImportCommand(
                new RegionBoundarySourceManifest(
                        "region-rev-2026-09-15",
                        "https://www.data.go.kr/data/15083799/fileData.do",
                        "공공누리 제1유형",
                        "행정안전부 법정동 경계 데이터",
                        Instant.parse("2026-09-15T03:00:00Z"),
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                List.of(
                        sido("kr-11", "서울특별시", box(126.75, 37.42, 127.20, 37.72)),
                        sigungu("kr-11-jongno", "kr-11", "종로구", box(126.96, 37.56, 127.02, 37.62)),
                        sido("kr-45", "전북특별자치도", box(126.90, 35.50, 127.80, 36.20)),
                        sigungu("kr-45-jeonju", "kr-45", "전주시", box(127.05, 35.70, 127.25, 35.90))
                )));
        return store;
    }

    @Bean
    RegionCatalog regionCatalog(InMemoryRegionBoundaryStore regionBoundaryStore) {
        return new CatalogRegionCatalogAdapter(regionBoundaryStore);
    }

    @Bean
    VisitReviewRegionReadService visitReviewRegionReadService(
            VisitReviewStore visitReviewStore,
            RegionCatalog regionCatalog,
            Clock clock) {
        return new VisitReviewRegionReadService(visitReviewStore, regionCatalog, clock);
    }

    private RegionBoundaryCandidate sido(String code, String name, RegionBoundaryGeometry geometry) {
        return new RegionBoundaryCandidate(code, null, name, RegionBoundaryLevel.SIDO, geometry);
    }

    private RegionBoundaryCandidate sigungu(String code, String parentCode, String name, RegionBoundaryGeometry geometry) {
        return new RegionBoundaryCandidate(code, parentCode, name, RegionBoundaryLevel.SIGUNGU, geometry);
    }

    private RegionBoundaryGeometry box(double west, double south, double east, double north) {
        return new RegionBoundaryGeometry(List.of(List.of(
                new BoundaryPoint(west, south),
                new BoundaryPoint(east, south),
                new BoundaryPoint(east, north),
                new BoundaryPoint(west, north),
                new BoundaryPoint(west, south)
        )));
    }
}

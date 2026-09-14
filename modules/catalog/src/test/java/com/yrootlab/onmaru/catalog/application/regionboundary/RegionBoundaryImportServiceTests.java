package com.yrootlab.onmaru.catalog.application.regionboundary;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegionBoundaryImportServiceTests {

    private final InMemoryRegionBoundaryStore store = new InMemoryRegionBoundaryStore();
    private final RegionBoundaryImportService service = new RegionBoundaryImportService(store);

    @Test
    void importsQualifiedRevisionWithSourceEvidenceAndResolvesSameNameDistrictsAndBoundaryPoint() {
        RegionBoundarySourceManifest manifest = manifest("seoul-busan-20260915");
        RegionBoundaryImportResult result = service.importAndActivate(new RegionBoundaryImportCommand(
                manifest,
                List.of(
                        sido("11", "서울특별시", box(126.75, 37.42, 127.20, 37.72)),
                        sigungu("11110", "11", "중구", box(126.96, 37.54, 127.02, 37.58)),
                        sido("26", "부산광역시", box(128.75, 35.00, 129.35, 35.35)),
                        sigungu("26110", "26", "중구", box(129.02, 35.08, 129.06, 35.13)),
                        sigungu("26440", "26", "강서구", box(128.75, 35.05, 128.95, 35.25)),
                        sido("41", "경기도", box(126.50, 36.80, 127.90, 38.30)),
                        sigungu("41610", "41", "광주시", box(127.16, 37.28, 127.42, 37.51))
                )
        ));

        assertThat(result.status()).isEqualTo(RegionBoundaryImportStatus.ACTIVATED);
        assertThat(result.acceptedCount()).isEqualTo(7);
        assertThat(result.quarantine()).isEmpty();

        RegionBoundaryRevision activeRevision = store.activeRevision().orElseThrow();
        assertThat(activeRevision.revisionId()).isEqualTo("seoul-busan-20260915");
        assertThat(activeRevision.source().datasetUrl()).isEqualTo("https://www.data.go.kr/data/15083799/fileData.do");
        assertThat(activeRevision.source().license()).isEqualTo("공공누리 제1유형");
        assertThat(activeRevision.source().attribution()).isEqualTo("행정안전부 법정동 경계 데이터");
        assertThat(activeRevision.source().revisionHash()).hasSize(64);

        List<RegionBoundaryProjection> busanJungGu = store.resolve(129.04, 35.10);
        assertThat(busanJungGu)
                .extracting(RegionBoundaryProjection::regionCode)
                .containsExactly("26", "26110");
        assertThat(busanJungGu.get(1).name()).isEqualTo("중구");
        assertThat(busanJungGu.get(1).parentRegionCode()).isEqualTo("26");

        List<RegionBoundaryProjection> borderPoint = store.resolve(127.02, 37.56);
        assertThat(borderPoint)
                .extracting(RegionBoundaryProjection::regionCode)
                .contains("11110");
    }

    @Test
    void quarantinesInvalidGeometryAndParentlessCodeWithoutActivatingInvalidRows() {
        RegionBoundaryImportResult result = service.importAndActivate(new RegionBoundaryImportCommand(
                manifest("invalid-rows-20260915"),
                List.of(
                        sido("11", "서울특별시", box(126.75, 37.42, 127.20, 37.72)),
                        sigungu("11110", "11", "종로구", box(126.96, 37.56, 127.02, 37.62)),
                        sigungu("99999", "99", "부모없는구", box(127.10, 37.50, 127.12, 37.52)),
                        sigungu("11140", "11", "깨진구", openRing(126.98, 37.55, 127.00, 37.58)),
                        sigungu("11170", "11", "해외구", box(-122.45, 37.70, -122.40, 37.78))
                )
        ));

        assertThat(result.status()).isEqualTo(RegionBoundaryImportStatus.ACTIVATED_WITH_QUARANTINE);
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(result.quarantine())
                .extracting(RegionBoundaryQuarantine::errorCode)
                .containsExactlyInAnyOrder("PARENT_REGION_NOT_FOUND", "INVALID_GEOMETRY", "INVALID_GEOMETRY");

        assertThat(store.resolve(127.11, 37.51))
                .extracting(RegionBoundaryProjection::regionCode)
                .doesNotContain("99999");
        assertThat(store.activeRevision().orElseThrow().boundaries())
                .extracting(RegionBoundaryProjection::regionCode)
                .doesNotContain("99999", "11140", "11170");
    }

    private RegionBoundarySourceManifest manifest(String revisionId) {
        return new RegionBoundarySourceManifest(
                revisionId,
                "https://www.data.go.kr/data/15083799/fileData.do",
                "공공누리 제1유형",
                "행정안전부 법정동 경계 데이터",
                Instant.parse("2026-09-15T03:00:00Z"),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        );
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

    private RegionBoundaryGeometry openRing(double west, double south, double east, double north) {
        return new RegionBoundaryGeometry(List.of(List.of(
                new BoundaryPoint(west, south),
                new BoundaryPoint(east, south),
                new BoundaryPoint(east, north),
                new BoundaryPoint(west, north)
        )));
    }
}

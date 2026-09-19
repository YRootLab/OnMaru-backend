package com.yrootlab.onmaru.community.region;

import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisitReviewRegionReadServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-15T08:30:00Z");

    private final InMemoryVisitReviewStore reviewStore = new InMemoryVisitReviewStore();
    private final FakeRegionCatalog regionCatalog = new FakeRegionCatalog();
    private final VisitReviewRegionReadService service = new VisitReviewRegionReadService(
            reviewStore,
            regionCatalog,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void listsProvinceCountsUsingPublishedLeafReviewsOnlyAndCountsUnassignedAtRoot() {
        seedReviews();

        RegionCountPage page = service.listRegions(null);

        assertThat(page.schemaVersion()).isEqualTo("1.2");
        assertThat(page.regionRevision()).isEqualTo("region-rev-test");
        assertThat(page.countsAsOf()).isEqualTo(NOW);
        assertThat(page.parentRegionCode()).isNull();
        assertThat(page.unassignedCount()).isEqualTo(1);
        assertThat(page.items())
                .extracting(item -> item.region().regionCode(), RegionReviewCountItem::reviewCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("kr-11", 1L),
                        org.assertj.core.groups.Tuple.tuple("kr-26", 1L),
                        org.assertj.core.groups.Tuple.tuple("kr-41", 1L),
                        org.assertj.core.groups.Tuple.tuple("kr-45", 2L)
                );
    }

    @Test
    void listsDirectCityChildrenForProvinceAndRejectsLeafParent() {
        seedReviews();

        RegionCountPage page = service.listRegions("kr-45");

        assertThat(page.parentRegionCode()).isEqualTo("kr-45");
        assertThat(page.unassignedCount()).isZero();
        assertThat(page.items())
                .extracting(item -> item.region().regionCode(), RegionReviewCountItem::reviewCount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("kr-45-jeonju", 2L));

        assertThatThrownBy(() -> service.listRegions("kr-45-jeonju"))
                .isInstanceOf(VisitReviewRegionInvalidRequestException.class)
                .hasMessage("parentRegionCode");
    }

    @Test
    void resolvesCoordinatesIncludingBoundaryPointWithoutPersistingLocation() {
        RegionResolution resolution = service.resolve(37.56, 127.02);

        assertThat(resolution.schemaVersion()).isEqualTo("1.2");
        assertThat(resolution.coordinates()).isEqualTo(new RegionCoordinates(37.56, 127.02));
        assertThat(resolution.candidates())
                .extracting(candidate -> candidate.region().regionCode())
                .containsExactly("kr-11", "kr-11-jung");
        assertThat(resolution.candidates())
                .extracting(RegionResolutionCandidate::confidence)
                .containsExactly(0.99, 0.99);
    }

    @Test
    void invalidCoordinatesAndMissingRevisionArePublicErrors() {
        assertThatThrownBy(() -> service.resolve(91.0, 127.0))
                .isInstanceOf(VisitReviewRegionInvalidRequestException.class)
                .hasMessage("lat");

        regionCatalog.available = false;

        assertThatThrownBy(() -> service.listRegions(null))
                .isInstanceOf(VisitReviewRegionUnavailableException.class);
    }

    private void seedReviews() {
        reviewStore.add(review("00000000-0000-0000-0000-000000000001", "kr-45-jeonju", VisitReviewStatus.PUBLISHED));
        reviewStore.add(review("00000000-0000-0000-0000-000000000002", "kr-45-jeonju", VisitReviewStatus.PUBLISHED));
        reviewStore.add(review("00000000-0000-0000-0000-000000000003", "kr-11-jung", VisitReviewStatus.PUBLISHED));
        reviewStore.add(review("00000000-0000-0000-0000-000000000004", "kr-26-jung", VisitReviewStatus.PUBLISHED));
        reviewStore.add(review("00000000-0000-0000-0000-000000000005", "kr-41-gwangju", VisitReviewStatus.PUBLISHED));
        reviewStore.add(review("00000000-0000-0000-0000-000000000006", "kr-99-unknown", VisitReviewStatus.PUBLISHED));
        reviewStore.add(review("00000000-0000-0000-0000-000000000007", "kr-45-jeonju", VisitReviewStatus.HIDDEN));
    }

    private VisitReviewProjection review(String id, String regionCode, VisitReviewStatus status) {
        return new VisitReviewProjection(
                UUID.fromString(id),
                "p-test",
                "테스트 장소",
                regionCode,
                35.8151,
                127.1530,
                "좋았습니다.",
                NOW.minusSeconds(UUID.fromString(id).getLeastSignificantBits()),
                UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712"),
                Set.of(),
                status);
    }

    private static final class FakeRegionCatalog implements RegionCatalog {

        private final List<RegionProjection> regions = List.of(
                new RegionProjection("kr-11", null, "서울특별시", RegionLevel.PROVINCE),
                new RegionProjection("kr-11-jung", "kr-11", "중구", RegionLevel.CITY),
                new RegionProjection("kr-26", null, "부산광역시", RegionLevel.PROVINCE),
                new RegionProjection("kr-26-jung", "kr-26", "중구", RegionLevel.CITY),
                new RegionProjection("kr-41", null, "경기도", RegionLevel.PROVINCE),
                new RegionProjection("kr-41-gwangju", "kr-41", "광주시", RegionLevel.CITY),
                new RegionProjection("kr-45", null, "전북특별자치도", RegionLevel.PROVINCE),
                new RegionProjection("kr-45-jeonju", "kr-45", "전주시", RegionLevel.CITY)
        );
        private boolean available = true;

        @Override
        public Optional<String> activeRevisionId() {
            return available ? Optional.of("region-rev-test") : Optional.empty();
        }

        @Override
        public List<RegionProjection> regions() {
            return available ? regions : List.of();
        }

        @Override
        public List<RegionProjection> resolve(double latitude, double longitude) {
            if (!available) {
                return List.of();
            }
            if (latitude == 37.56 && longitude == 127.02) {
                return List.of(regions.get(0), regions.get(1));
            }
            return List.of();
        }
    }
}

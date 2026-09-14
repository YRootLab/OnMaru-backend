package com.yrootlab.onmaru.community.region;

import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.community.query.VisitReviewStore;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class VisitReviewRegionReadService {

    private static final String SCHEMA_VERSION = "1.2";

    private final VisitReviewStore reviewStore;
    private final RegionCatalog regionCatalog;
    private final Clock clock;

    public VisitReviewRegionReadService(
            VisitReviewStore reviewStore,
            RegionCatalog regionCatalog,
            Clock clock) {
        this.reviewStore = reviewStore;
        this.regionCatalog = regionCatalog;
        this.clock = clock;
    }

    public RegionCountPage listRegions(String parentRegionCode) {
        String revisionId = activeRevisionId();
        List<RegionProjection> regions = regionCatalog.regions();
        Map<String, RegionProjection> byCode = regions.stream()
                .collect(Collectors.toMap(RegionProjection::regionCode, Function.identity()));
        String normalizedParent = normalize(parentRegionCode);
        validateParent(normalizedParent, byCode);

        List<RegionProjection> children = regions.stream()
                .filter(region -> matchesParent(region, normalizedParent))
                .sorted(Comparator.comparing(RegionProjection::regionCode))
                .toList();
        if (normalizedParent != null && children.isEmpty()) {
            throw new VisitReviewRegionInvalidRequestException("parentRegionCode");
        }

        Set<String> knownLeafRegionCodes = regions.stream()
                .filter(region -> region.level() == RegionLevel.CITY || region.level() == RegionLevel.DISTRICT)
                .map(RegionProjection::regionCode)
                .collect(Collectors.toSet());
        List<VisitReviewProjection> publishedReviews = reviewStore.findSnapshot().stream()
                .filter(review -> review.status() == VisitReviewStatus.PUBLISHED)
                .toList();

        List<RegionReviewCountItem> items = children.stream()
                .map(region -> new RegionReviewCountItem(region, countForRegion(region, regions, publishedReviews)))
                .toList();
        long unassignedCount = normalizedParent == null
                ? publishedReviews.stream().filter(review -> !knownLeafRegionCodes.contains(review.regionCode())).count()
                : 0L;

        return new RegionCountPage(
                SCHEMA_VERSION,
                revisionId,
                clock.instant(),
                normalizedParent,
                unassignedCount,
                items);
    }

    public RegionResolution resolve(double latitude, double longitude) {
        String revisionId = activeRevisionId();
        validateCoordinates(latitude, longitude);
        List<RegionResolutionCandidate> candidates = regionCatalog.resolve(latitude, longitude).stream()
                .map(region -> new RegionResolutionCandidate(region, 0.99))
                .toList();
        return new RegionResolution(
                SCHEMA_VERSION,
                revisionId,
                new RegionCoordinates(latitude, longitude),
                candidates,
                clock.instant());
    }

    private long countForRegion(
            RegionProjection region,
            List<RegionProjection> regions,
            List<VisitReviewProjection> publishedReviews) {
        Set<String> leafCodes = descendantLeafCodes(region, regions);
        return publishedReviews.stream()
                .filter(review -> leafCodes.contains(review.regionCode()))
                .count();
    }

    private Set<String> descendantLeafCodes(RegionProjection parent, List<RegionProjection> regions) {
        if (parent.level() == RegionLevel.CITY || parent.level() == RegionLevel.DISTRICT) {
            return Set.of(parent.regionCode());
        }
        return regions.stream()
                .filter(region -> parent.regionCode().equals(region.parentRegionCode()))
                .filter(region -> region.level() == RegionLevel.CITY || region.level() == RegionLevel.DISTRICT)
                .map(RegionProjection::regionCode)
                .collect(Collectors.toSet());
    }

    private String activeRevisionId() {
        Optional<String> revisionId = regionCatalog.activeRevisionId();
        if (revisionId.isEmpty()) {
            throw new VisitReviewRegionUnavailableException();
        }
        return revisionId.orElseThrow();
    }

    private void validateParent(String parentRegionCode, Map<String, RegionProjection> byCode) {
        if (parentRegionCode == null) {
            return;
        }
        RegionProjection parent = byCode.get(parentRegionCode);
        if (parent == null || parent.level() != RegionLevel.PROVINCE) {
            throw new VisitReviewRegionInvalidRequestException("parentRegionCode");
        }
    }

    private boolean matchesParent(RegionProjection region, String parentRegionCode) {
        if (parentRegionCode == null) {
            return region.parentRegionCode() == null && region.level() == RegionLevel.PROVINCE;
        }
        return parentRegionCode.equals(region.parentRegionCode());
    }

    private void validateCoordinates(double latitude, double longitude) {
        if (latitude < -90.0 || latitude > 90.0) {
            throw new VisitReviewRegionInvalidRequestException("lat");
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new VisitReviewRegionInvalidRequestException("lng");
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

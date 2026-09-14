package com.yrootlab.onmaru.catalog.application.query.spatial;

import java.util.Comparator;

public final class MapPlaceQueryService {

    private static final String SCHEMA_VERSION = "1.2";
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final MapPlaceStore store;
    private final MapSavedStateLookup savedStateLookup;

    public MapPlaceQueryService(MapPlaceStore store, MapSavedStateLookup savedStateLookup) {
        this.store = store;
        this.savedStateLookup = savedStateLookup;
    }

    public MapPlacePage list(MapPlaceQuery query) {
        validate(query);
        var filtered = store.findPublishedSnapshot().stream()
                .filter(projection -> projection.status() == MapPlaceStatus.PUBLIC)
                .filter(projection -> projection.coordinates() != null)
                .filter(projection -> query.regionCode() == null || query.regionCode().equals(projection.region().regionCode()))
                .filter(projection -> query.category() == null || query.category().equals(projection.category()))
                .filter(projection -> query.bbox() == null || inBbox(projection.coordinates(), query.bbox()))
                .filter(projection -> query.radiusMeters() == null
                        || distanceMeters(query.lat(), query.lng(), projection.coordinates()) <= query.radiusMeters())
                .sorted(order(query))
                .limit(query.limit() + 1L)
                .toList();
        boolean hasMore = filtered.size() > query.limit();
        var pageItems = hasMore ? filtered.subList(0, query.limit()) : filtered;
        var cards = pageItems.stream()
                .map(projection -> new MapPlaceCard(
                        projection.placeId(),
                        projection.name(),
                        projection.category(),
                        projection.region(),
                        projection.coordinates(),
                        projection.thumbnailUrl(),
                        projection.summary(),
                        savedStateLookup.savedBy(query.memberId(), projection.placeId()),
                        projection.linkedOdiiStoryIds(),
                        projection.dataAvailability()))
                .toList();
        var coverage = cards.isEmpty() ? MapCoverageStatus.MISSING : pageCoverage(cards, hasMore);
        return new MapPlacePage(SCHEMA_VERSION, coverage, query.language(), cards, null, hasMore);
    }

    private void validate(MapPlaceQuery query) {
        if (query.limit() < 1 || query.limit() > 100) {
            throw new MapPlaceInvalidRequestException("limit");
        }
        if ((query.lat() == null) != (query.lng() == null)) {
            throw new MapPlaceInvalidRequestException("coordinates");
        }
        if (query.lat() != null) {
            validateLatitude(query.lat(), "lat");
            validateLongitude(query.lng(), "lng");
            if (query.radiusMeters() == null || query.radiusMeters() < 1 || query.radiusMeters() > 100_000) {
                throw new MapPlaceInvalidRequestException("radius");
            }
        } else if (query.radiusMeters() != null) {
            throw new MapPlaceInvalidRequestException("radius");
        }
        if (query.bbox() != null) {
            validateBbox(query.bbox());
        }
    }

    private void validateBbox(MapBoundingBox bbox) {
        validateLongitude(bbox.west(), "bbox");
        validateLongitude(bbox.east(), "bbox");
        validateLatitude(bbox.south(), "bbox");
        validateLatitude(bbox.north(), "bbox");
        if (bbox.west() >= bbox.east() || bbox.south() >= bbox.north()) {
            throw new MapPlaceInvalidRequestException("bbox");
        }
    }

    private void validateLatitude(double value, String field) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < -90 || value > 90) {
            throw new MapPlaceInvalidRequestException(field);
        }
    }

    private void validateLongitude(double value, String field) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < -180 || value > 180) {
            throw new MapPlaceInvalidRequestException(field);
        }
    }

    private Comparator<MapPlaceProjection> order(MapPlaceQuery query) {
        if (query.lat() != null) {
            return Comparator
                    .comparingDouble((MapPlaceProjection projection) -> distanceMeters(query.lat(), query.lng(), projection.coordinates()))
                    .thenComparing(MapPlaceProjection::placeId);
        }
        return (left, right) -> 0;
    }

    private boolean inBbox(MapCoordinates coordinates, MapBoundingBox bbox) {
        return coordinates.lng() >= bbox.west()
                && coordinates.lng() <= bbox.east()
                && coordinates.lat() >= bbox.south()
                && coordinates.lat() <= bbox.north();
    }

    private MapCoverageStatus pageCoverage(Iterable<MapPlaceCard> cards, boolean hasMore) {
        if (hasMore) {
            return MapCoverageStatus.PARTIAL;
        }
        for (MapPlaceCard card : cards) {
            if (card.dataAvailability().place() != MapCoverageStatus.COMPLETE
                    || card.dataAvailability().observation() != MapCoverageStatus.COMPLETE
                    || card.dataAvailability().odii() != MapCoverageStatus.COMPLETE) {
                return MapCoverageStatus.PARTIAL;
            }
        }
        return MapCoverageStatus.COMPLETE;
    }

    private double distanceMeters(double lat, double lng, MapCoordinates target) {
        double sourceLat = Math.toRadians(lat);
        double targetLat = Math.toRadians(target.lat());
        double deltaLat = targetLat - sourceLat;
        double deltaLng = Math.toRadians(target.lng() - lng);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(sourceLat) * Math.cos(targetLat)
                * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}

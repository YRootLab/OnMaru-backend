package com.yrootlab.onmaru.catalog.application.regionboundary;

import java.util.List;

public record RegionBoundaryGeometry(List<List<BoundaryPoint>> polygons) {

    private static final double EPSILON = 0.000000001;

    public RegionBoundaryGeometry {
        polygons = List.copyOf(polygons);
    }

    boolean isValid() {
        if (polygons.isEmpty()) {
            return false;
        }
        return polygons.stream().allMatch(this::isValidPolygon);
    }

    boolean contains(double longitude, double latitude) {
        return polygons.stream().anyMatch(polygon -> contains(polygon, longitude, latitude));
    }

    private boolean isValidPolygon(List<BoundaryPoint> polygon) {
        if (polygon.size() < 4 || !isClosed(polygon)) {
            return false;
        }
        for (BoundaryPoint point : polygon) {
            if (!isKoreaCoordinate(point.longitude(), point.latitude())) {
                return false;
            }
        }
        return Math.abs(signedArea(polygon)) > EPSILON;
    }

    private boolean isClosed(List<BoundaryPoint> polygon) {
        BoundaryPoint first = polygon.get(0);
        BoundaryPoint last = polygon.get(polygon.size() - 1);
        return nearlyEqual(first.longitude(), last.longitude()) && nearlyEqual(first.latitude(), last.latitude());
    }

    private boolean contains(List<BoundaryPoint> polygon, double longitude, double latitude) {
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            BoundaryPoint current = polygon.get(i);
            BoundaryPoint previous = polygon.get(j);
            if (isOnSegment(previous, current, longitude, latitude)) {
                return true;
            }
            boolean crossesLatitude = (current.latitude() > latitude) != (previous.latitude() > latitude);
            if (crossesLatitude) {
                double intersectionLongitude = (previous.longitude() - current.longitude())
                        * (latitude - current.latitude())
                        / (previous.latitude() - current.latitude())
                        + current.longitude();
                if (longitude < intersectionLongitude) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    private boolean isOnSegment(BoundaryPoint start, BoundaryPoint end, double longitude, double latitude) {
        double cross = (latitude - start.latitude()) * (end.longitude() - start.longitude())
                - (longitude - start.longitude()) * (end.latitude() - start.latitude());
        if (Math.abs(cross) > EPSILON) {
            return false;
        }
        return longitude >= Math.min(start.longitude(), end.longitude()) - EPSILON
                && longitude <= Math.max(start.longitude(), end.longitude()) + EPSILON
                && latitude >= Math.min(start.latitude(), end.latitude()) - EPSILON
                && latitude <= Math.max(start.latitude(), end.latitude()) + EPSILON;
    }

    private double signedArea(List<BoundaryPoint> polygon) {
        double area = 0.0;
        for (int i = 0; i < polygon.size() - 1; i++) {
            BoundaryPoint current = polygon.get(i);
            BoundaryPoint next = polygon.get(i + 1);
            area += current.longitude() * next.latitude() - next.longitude() * current.latitude();
        }
        return area / 2.0;
    }

    private boolean isKoreaCoordinate(double longitude, double latitude) {
        return longitude >= 124.0 && longitude <= 132.0 && latitude >= 33.0 && latitude <= 39.0;
    }

    private boolean nearlyEqual(double left, double right) {
        return Math.abs(left - right) <= EPSILON;
    }
}

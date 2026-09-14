package com.yrootlab.onmaru.catalog.application.query.detail;

import java.util.List;

public record PlaceProjection(
        String placeId,
        String name,
        String category,
        RegionProjection region,
        String address,
        CoordinatesProjection coordinates,
        List<ImageProjection> images,
        String description,
        List<String> highlights,
        String odiiLinkedResourceId,
        PlaceProjectionStatus status,
        boolean ambiguousMapping) {

    public static PlaceProjection publicPlace(
            String placeId,
            String name,
            String category,
            RegionProjection region,
            String address,
            CoordinatesProjection coordinates,
            List<ImageProjection> images,
            String description,
            List<String> highlights,
            String odiiLinkedResourceId) {
        return new PlaceProjection(
                placeId,
                name,
                category,
                region,
                address,
                coordinates,
                List.copyOf(images),
                description,
                List.copyOf(highlights),
                odiiLinkedResourceId,
                PlaceProjectionStatus.PUBLIC,
                false);
    }

    public static PlaceProjection hidden(String placeId) {
        return unavailable(placeId, PlaceProjectionStatus.HIDDEN, false);
    }

    public static PlaceProjection deleted(String placeId) {
        return unavailable(placeId, PlaceProjectionStatus.DELETED, false);
    }

    public static PlaceProjection ambiguous(String placeId) {
        return unavailable(placeId, PlaceProjectionStatus.PUBLIC, true);
    }

    private static PlaceProjection unavailable(
            String placeId,
            PlaceProjectionStatus status,
            boolean ambiguousMapping) {
        return new PlaceProjection(
                placeId,
                "",
                "",
                null,
                null,
                null,
                List.of(),
                "",
                List.of(),
                null,
                status,
                ambiguousMapping);
    }
}

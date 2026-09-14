package com.yrootlab.onmaru.catalog.application.query.detail;

import java.util.List;

public record HanokDetail(
        String schemaVersion,
        String placeId,
        String name,
        HanokCategory category,
        RegionProjection region,
        String address,
        CoordinatesProjection coordinates,
        List<ImageProjection> images,
        String description,
        List<String> highlights,
        boolean savedByMe,
        LinkedPlaceCard mapCard,
        LinkedPlaceCard odiiLinkedCard) {
}

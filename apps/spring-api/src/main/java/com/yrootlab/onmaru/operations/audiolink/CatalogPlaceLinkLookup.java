package com.yrootlab.onmaru.operations.audiolink;

import com.yrootlab.onmaru.audio.placelink.CanonicalPlaceLinkCard;
import com.yrootlab.onmaru.audio.placelink.CanonicalPlaceLinkLookup;
import com.yrootlab.onmaru.catalog.application.query.detail.CanonicalPlaceDetail;
import com.yrootlab.onmaru.catalog.application.query.detail.ImageProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailQueryService;

import java.util.Optional;
import java.util.UUID;

public final class CatalogPlaceLinkLookup implements CanonicalPlaceLinkLookup {

    private final PlaceDetailQueryService placeDetailQueryService;

    public CatalogPlaceLinkLookup(PlaceDetailQueryService placeDetailQueryService) {
        this.placeDetailQueryService = placeDetailQueryService;
    }

    @Override
    public Optional<CanonicalPlaceLinkCard> findPublicPlace(
            String placeId,
            Optional<UUID> memberId) {
        return placeDetailQueryService.findCanonicalPlace(placeId, memberId)
                .map(this::toCard);
    }

    private CanonicalPlaceLinkCard toCard(CanonicalPlaceDetail place) {
        return new CanonicalPlaceLinkCard(
                place.placeId(),
                place.name(),
                place.category(),
                place.region().name(),
                place.images().stream().findFirst().map(ImageProjection::url).orElse(null),
                place.savedByMe());
    }
}

package com.yrootlab.onmaru.catalog.application.query.detail;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListEligibility;

import java.util.Optional;

public interface PlaceDetailStore {

    Optional<PlaceProjection> findByPlaceId(String placeId);

    default Optional<PlaceProjection> findHanokByPlaceId(String placeId) {
        return findByPlaceId(placeId).filter(projection -> HanokListEligibility.matches(
                projection.category(), projection.name(), projection.description()));
    }
}

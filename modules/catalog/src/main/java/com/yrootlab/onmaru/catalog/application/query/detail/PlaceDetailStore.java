package com.yrootlab.onmaru.catalog.application.query.detail;

import java.util.Optional;

public interface PlaceDetailStore {

    Optional<PlaceProjection> findByPlaceId(String placeId);
}

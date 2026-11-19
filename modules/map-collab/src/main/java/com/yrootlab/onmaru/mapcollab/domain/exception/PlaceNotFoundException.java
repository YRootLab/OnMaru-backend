package com.yrootlab.onmaru.mapcollab.domain.exception;

import java.util.Map;

public final class PlaceNotFoundException extends MapCollabException {
    public PlaceNotFoundException(String placeId) {
        super("PLACE_NOT_FOUND", 404, "place not found", Map.of("placeId", placeId == null ? "" : placeId));
    }
}

package com.yrootlab.onmaru.catalog.externalplace;

import java.text.Normalizer;

public final class ExternalPlacePolicy {

    private static final int MAX_EXTERNAL_ID_CODE_POINTS = 128;
    private static final int MAX_NAME_CODE_POINTS = 100;
    private static final double MIN_LAT = 32.0;
    private static final double MAX_LAT = 39.5;
    private static final double MIN_LNG = 123.0;
    private static final double MAX_LNG = 132.0;

    public ExternalPlaceCandidate validate(ExternalPlaceCandidate candidate) {
        if (candidate == null) {
            throw error("place", "REQUIRED");
        }
        if (candidate.provider() == null) {
            throw error("place.provider", "REQUIRED");
        }
        var externalId = normalize(candidate.externalId(), "place.externalId");
        var name = normalize(candidate.name(), "place.name");
        requireMaxLength(externalId, MAX_EXTERNAL_ID_CODE_POINTS, "place.externalId");
        requireMaxLength(name, MAX_NAME_CODE_POINTS, "place.name");
        validateCoordinates(candidate.lat(), candidate.lng());
        return new ExternalPlaceCandidate(candidate.provider(), externalId, name, candidate.lat(), candidate.lng());
    }

    private String normalize(String raw, String field) {
        if (raw == null) {
            throw error(field, "REQUIRED");
        }
        var value = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC);
        if (value.isBlank()) {
            throw error(field, "REQUIRED");
        }
        return value;
    }

    private void requireMaxLength(String value, int maxLength, String field) {
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw error(field, "TOO_LONG");
        }
    }

    private void validateCoordinates(double lat, double lng) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng)
                || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw error("place.location", "INVALID_COORDINATE");
        }
        if (lat < MIN_LAT || lat > MAX_LAT || lng < MIN_LNG || lng > MAX_LNG) {
            throw error("place.location", "OUTSIDE_SERVICE_AREA");
        }
    }

    private ExternalPlaceValidationException error(String field, String reason) {
        return new ExternalPlaceValidationException(field, reason);
    }
}

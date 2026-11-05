package com.yrootlab.onmaru.journey.observation;

public enum ObservationRoute {
    SQL_ONLY,
    LEXICAL_ONLY,
    DENSE_ONLY,
    HYBRID,
    SQL_PLUS_RETRIEVAL,
    CLARIFICATION,
    UNKNOWN;

    public static ObservationRoute fromString(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        for (var route : values()) {
            if (route.name().equalsIgnoreCase(value.trim())) {
                return route;
            }
        }
        return UNKNOWN;
    }
}

package com.yrootlab.onmaru.journey.observation;

public enum ObservationOutcome {
    SUCCESS,
    DEGRADED,
    FAILED,
    CANCELLED,
    UNKNOWN;

    public static ObservationOutcome fromString(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        for (var outcome : values()) {
            if (outcome.name().equalsIgnoreCase(value.trim())) {
                return outcome;
            }
        }
        return UNKNOWN;
    }
}

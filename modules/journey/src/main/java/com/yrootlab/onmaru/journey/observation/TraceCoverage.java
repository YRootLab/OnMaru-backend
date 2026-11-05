package com.yrootlab.onmaru.journey.observation;

public enum TraceCoverage {
    AVAILABLE,
    SAMPLED_OUT,
    EXPORT_FAILED,
    EXPIRED,
    UNKNOWN;

    public static TraceCoverage fromString(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        for (var coverage : values()) {
            if (coverage.name().equalsIgnoreCase(value.trim())) {
                return coverage;
            }
        }
        return UNKNOWN;
    }
}

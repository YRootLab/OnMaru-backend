package com.yrootlab.onmaru.journey.observation;

import java.util.Objects;

public enum ObservationStatus {
    COMPLETE,
    PARTIAL;

    public static ObservationStatus fromString(String value) {
        if (value == null || value.isBlank()) {
            return PARTIAL;
        }
        for (var status : values()) {
            if (status.name().equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        return PARTIAL;
    }
}

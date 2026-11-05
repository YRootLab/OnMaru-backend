package com.yrootlab.onmaru.journey.observation;

public enum StageExecutionStatus {
    SUCCESS,
    EMPTY,
    DEGRADED,
    FAILED,
    SKIPPED;

    public static StageExecutionStatus fromString(String value) {
        if (value == null || value.isBlank()) {
            return SUCCESS;
        }
        for (var status : values()) {
            if (status.name().equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        return SUCCESS;
    }
}

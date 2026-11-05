package com.yrootlab.onmaru.journey.observation;

import java.util.Objects;

public record StageObservation(
        String stage,
        StageExecutionStatus status,
        String errorCode,
        String reasonCode,
        Integer durationMs,
        Integer candidateCount,
        Integer evidenceCount
) {
    public StageObservation {
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public static StageObservation of(
            String stage,
            StageExecutionStatus status,
            String errorCode,
            String reasonCode,
            Integer durationMs,
            Integer candidateCount,
            Integer evidenceCount
    ) {
        return new StageObservation(stage, status, errorCode, reasonCode, durationMs, candidateCount, evidenceCount);
    }
}

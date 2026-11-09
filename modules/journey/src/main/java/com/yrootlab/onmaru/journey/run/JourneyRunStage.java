package com.yrootlab.onmaru.journey.run;

/**
 * Public journey stages, in forward order. These match the OpenAPI/SSE 1.3 stage enum (see
 * docs/contracts/openapi/journey.openapi.yaml), so AI progress and worker progress share one vocabulary.
 */
public enum JourneyRunStage {
    INTERPRETING,
    RETRIEVING,
    SELECTING_EVIDENCE,
    GENERATING,
    VALIDATING,
    PERSISTING;

    /** Stages only move forward; a step may skip stages that the AI progress already covered. */
    boolean follows(JourneyRunStage previous) {
        return previous == null ? this == INTERPRETING : ordinal() > previous.ordinal();
    }
}

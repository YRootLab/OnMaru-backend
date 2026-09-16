package com.yrootlab.onmaru.journey.run;

public enum JourneyRunStage {
    INTERPRETING,
    RETRIEVING,
    VALIDATING,
    PERSISTING;

    boolean follows(JourneyRunStage previous) {
        return ordinal() == (previous == null ? 0 : previous.ordinal() + 1);
    }
}

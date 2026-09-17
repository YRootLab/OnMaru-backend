package com.yrootlab.onmaru.journey.savedjourney;

public final class SavedJourneyLimitExceededException extends RuntimeException {
    private final int limit;

    public SavedJourneyLimitExceededException(int limit) {
        super("saved journey limit exceeded: " + limit);
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}

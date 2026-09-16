package com.yrootlab.onmaru.journey.saved.odii;

public final class SavedOdiiStoryLimitExceededException extends RuntimeException {

    private final int limit;

    public SavedOdiiStoryLimitExceededException(int limit) {
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}

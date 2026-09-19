package com.yrootlab.onmaru.journey.saved.place;

public final class SavedPlaceLimitExceededException extends RuntimeException {

    private final int limit;

    public SavedPlaceLimitExceededException(int limit) {
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}

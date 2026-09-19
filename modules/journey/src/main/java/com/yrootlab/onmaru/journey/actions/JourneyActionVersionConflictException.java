package com.yrootlab.onmaru.journey.actions;

public final class JourneyActionVersionConflictException extends RuntimeException {
    private final int currentVersion;

    public JourneyActionVersionConflictException(int currentVersion) {
        super("journey action version is stale: " + currentVersion);
        this.currentVersion = currentVersion;
    }

    public int currentVersion() { return currentVersion; }
}

package com.yrootlab.onmaru.journey.exploration;

public final class ExplorationVersionConflictException extends RuntimeException {

    private final int currentVersion;

    public ExplorationVersionConflictException(int currentVersion) {
        super("exploration version conflict");
        this.currentVersion = currentVersion;
    }

    public int currentVersion() {
        return currentVersion;
    }
}

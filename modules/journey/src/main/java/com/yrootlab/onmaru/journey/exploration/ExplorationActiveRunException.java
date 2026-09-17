package com.yrootlab.onmaru.journey.exploration;

import java.util.UUID;

public class ExplorationActiveRunException extends RuntimeException {

    private final UUID runId;

    public ExplorationActiveRunException() {
        this(null);
    }

    public ExplorationActiveRunException(UUID runId) {
        super("exploration already has an active run");
        this.runId = runId;
    }

    public UUID runId() {
        return runId;
    }
}

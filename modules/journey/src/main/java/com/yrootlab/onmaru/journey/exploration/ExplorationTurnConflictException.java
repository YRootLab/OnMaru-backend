package com.yrootlab.onmaru.journey.exploration;

public final class ExplorationTurnConflictException extends RuntimeException {

    public ExplorationTurnConflictException() {
        super("client turn id conflicts with a previous turn");
    }
}

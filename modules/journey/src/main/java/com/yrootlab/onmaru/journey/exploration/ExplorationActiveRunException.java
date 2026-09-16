package com.yrootlab.onmaru.journey.exploration;

public class ExplorationActiveRunException extends RuntimeException {

    public ExplorationActiveRunException() {
        super("exploration already has an active run");
    }
}

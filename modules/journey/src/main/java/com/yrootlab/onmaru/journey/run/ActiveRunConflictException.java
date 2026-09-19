package com.yrootlab.onmaru.journey.run;

public final class ActiveRunConflictException extends RuntimeException {
    public ActiveRunConflictException() {
        super("an active run already exists");
    }
}

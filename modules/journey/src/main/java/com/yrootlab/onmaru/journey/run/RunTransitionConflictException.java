package com.yrootlab.onmaru.journey.run;

public final class RunTransitionConflictException extends RuntimeException {
    public RunTransitionConflictException() {
        super("run state transition compare-and-set failed");
    }
}

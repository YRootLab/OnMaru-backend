package com.yrootlab.onmaru.journey.run;

public final class RunCommandConflictException extends RuntimeException {
    public RunCommandConflictException() {
        super("command key was already used with a different payload");
    }
}

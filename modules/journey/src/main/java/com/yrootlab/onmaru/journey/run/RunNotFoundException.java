package com.yrootlab.onmaru.journey.run;

public final class RunNotFoundException extends RuntimeException {
    public RunNotFoundException() {
        super("run was not found");
    }
}

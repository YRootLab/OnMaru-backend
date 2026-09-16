package com.yrootlab.onmaru.journey.exploration;

public final class ExplorationInputRejectedException extends RuntimeException {

    private final String code;

    public ExplorationInputRejectedException(String code) {
        super("exploration input rejected: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

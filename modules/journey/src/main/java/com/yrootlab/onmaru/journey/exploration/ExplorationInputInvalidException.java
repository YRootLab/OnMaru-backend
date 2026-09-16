package com.yrootlab.onmaru.journey.exploration;

public final class ExplorationInputInvalidException extends RuntimeException {

    private final String field;

    public ExplorationInputInvalidException(String field) {
        super("invalid exploration input: " + field);
        this.field = field;
    }

    public String field() {
        return field;
    }
}

package com.yrootlab.onmaru.audio.query;

public final class OdiiStoryInvalidRequestException extends RuntimeException {

    private final String field;

    public OdiiStoryInvalidRequestException(String field) {
        super("Invalid Odii story query field: " + field);
        this.field = field;
    }

    public String field() {
        return field;
    }
}

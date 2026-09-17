package com.yrootlab.onmaru.journey.thread;

public final class JourneyThreadInputInvalidException extends RuntimeException {

    private final String field;

    public JourneyThreadInputInvalidException(String field) {
        super(field);
        this.field = field;
    }

    public String field() {
        return field;
    }
}

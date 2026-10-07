package com.yrootlab.onmaru.catalog.externalplace;

public final class ExternalPlaceValidationException extends RuntimeException {

    private final String field;
    private final String reason;

    public ExternalPlaceValidationException(String field, String reason) {
        super(reason);
        this.field = field;
        this.reason = reason;
    }

    public String field() {
        return field;
    }

    public String reason() {
        return reason;
    }
}

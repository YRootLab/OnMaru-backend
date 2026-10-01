package com.yrootlab.onmaru.catalog.application.query.mapinfo;

public final class MapInfoQueryException extends RuntimeException {
    private final String code;
    private final String field;

    public MapInfoQueryException(String code, String field) {
        super(code + ": " + field);
        this.code = code;
        this.field = field;
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }
}

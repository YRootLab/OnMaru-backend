package com.yrootlab.onmaru.catalog.application.query.mapinfo;

public final class MapInfoViewportInvalidRequestException extends RuntimeException {

    private final String field;

    public MapInfoViewportInvalidRequestException(String field) {
        super("Invalid map information viewport field: " + field);
        this.field = field;
    }

    public String field() {
        return field;
    }
}

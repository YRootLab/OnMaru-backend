package com.yrootlab.onmaru.catalog.application.query.spatial;

public final class MapPlaceInvalidRequestException extends RuntimeException {

    private final String field;

    public MapPlaceInvalidRequestException(String field) {
        super("Invalid map place query field: " + field);
        this.field = field;
    }

    public String field() {
        return field;
    }
}

package com.yrootlab.onmaru.catalog.externalplace;

public final class ExternalPlaceIdentityConflictException extends RuntimeException {

    public ExternalPlaceIdentityConflictException() {
        super("External place identity conflicts with its stored location");
    }
}

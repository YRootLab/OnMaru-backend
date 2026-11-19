package com.yrootlab.onmaru.mapcollab.domain.exception;

public final class UnauthorizedException extends MapCollabException {
    public UnauthorizedException(String message) {
        super("UNAUTHORIZED", 401, message);
    }

    public UnauthorizedException() {
        this("unauthorized");
    }
}

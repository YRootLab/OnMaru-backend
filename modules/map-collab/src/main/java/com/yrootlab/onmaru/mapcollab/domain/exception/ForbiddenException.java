package com.yrootlab.onmaru.mapcollab.domain.exception;

public final class ForbiddenException extends MapCollabException {
    public ForbiddenException(String message) {
        super("FORBIDDEN", 403, message);
    }

    public ForbiddenException() {
        this("forbidden");
    }
}

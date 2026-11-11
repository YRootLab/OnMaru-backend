package com.yrootlab.onmaru.mapcollab.domain.exception;

public final class OpConflictException extends MapCollabException {
    public OpConflictException() {
        super("OP_CONFLICT", 409, "opId already used with different payload");
    }
}

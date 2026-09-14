package com.yrootlab.onmaru.web.common.idempotency;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("idempotency key was already used with a different payload");
    }
}

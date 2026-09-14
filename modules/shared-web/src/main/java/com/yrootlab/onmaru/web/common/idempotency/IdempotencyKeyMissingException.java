package com.yrootlab.onmaru.web.common.idempotency;

public class IdempotencyKeyMissingException extends RuntimeException {

    public IdempotencyKeyMissingException() {
        super("Idempotency-Key header is required");
    }
}

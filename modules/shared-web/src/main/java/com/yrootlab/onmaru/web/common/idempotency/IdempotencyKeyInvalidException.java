package com.yrootlab.onmaru.web.common.idempotency;

public class IdempotencyKeyInvalidException extends RuntimeException {

    public IdempotencyKeyInvalidException() {
        super("Idempotency-Key header must be a UUID");
    }

    public IdempotencyKeyInvalidException(Throwable cause) {
        super("Idempotency-Key header must be a UUID", cause);
    }
}

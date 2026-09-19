package com.yrootlab.onmaru.web.common.idempotency;

import java.util.UUID;

public record IdempotencyKey(UUID value) {

    public static final String HEADER = "Idempotency-Key";

    public IdempotencyKey {
        if (value == null) {
            throw new IllegalArgumentException("idempotency key value must not be null");
        }
    }

    public static IdempotencyKey fromHeader(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new IdempotencyKeyMissingException();
        }

        try {
            return new IdempotencyKey(UUID.fromString(headerValue.trim()));
        } catch (IllegalArgumentException exception) {
            throw new IdempotencyKeyInvalidException(exception);
        }
    }
}

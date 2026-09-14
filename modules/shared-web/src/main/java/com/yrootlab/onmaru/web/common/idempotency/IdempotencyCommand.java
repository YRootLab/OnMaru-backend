package com.yrootlab.onmaru.web.common.idempotency;

import java.util.Locale;
import java.util.UUID;

public record IdempotencyCommand(
        UUID key,
        String subjectId,
        String method,
        String path,
        String payloadFingerprint) {

    public IdempotencyCommand {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("subjectId must not be blank");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (payloadFingerprint == null || payloadFingerprint.isBlank()) {
            throw new IllegalArgumentException("payloadFingerprint must not be blank");
        }
        method = method.toUpperCase(Locale.ROOT);
    }
}

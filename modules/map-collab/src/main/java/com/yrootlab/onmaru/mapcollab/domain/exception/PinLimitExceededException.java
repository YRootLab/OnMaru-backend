package com.yrootlab.onmaru.mapcollab.domain.exception;

import java.util.Map;

public final class PinLimitExceededException extends MapCollabException {
    public PinLimitExceededException() {
        super("PIN_LIMIT_EXCEEDED", 409, "active pin limit exceeded", Map.of("limit", 100));
    }
}

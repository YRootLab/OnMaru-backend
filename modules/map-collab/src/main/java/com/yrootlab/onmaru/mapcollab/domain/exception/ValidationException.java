package com.yrootlab.onmaru.mapcollab.domain.exception;

import java.util.Map;

public final class ValidationException extends MapCollabException {
    public ValidationException(String message) {
        super("VALIDATION_ERROR", 400, message);
    }

    public ValidationException(String message, Map<String, Object> details) {
        super("VALIDATION_ERROR", 400, message, details);
    }
}

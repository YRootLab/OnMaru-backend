package com.yrootlab.onmaru.web.common.error;

import org.springframework.http.HttpStatus;

public enum ApiErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),
    CURSOR_INVALID(HttpStatus.BAD_REQUEST, "Cursor is invalid"),
    CURSOR_EXPIRED(HttpStatus.GONE, "Cursor is expired"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Idempotency key conflicts with a previous request"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error");

    private final HttpStatus status;
    private final String message;

    ApiErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}

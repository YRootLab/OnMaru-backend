package com.yrootlab.onmaru.tourism.audio.client;

public final class OdiiParseException extends RuntimeException {

    private final boolean retryable;

    public OdiiParseException(String message) {
        this(message, false, null);
    }

    public OdiiParseException(String message, Throwable cause) {
        this(message, false, cause);
    }

    public OdiiParseException(String message, boolean retryable) {
        this(message, retryable, null);
    }

    private OdiiParseException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}

package com.yrootlab.onmaru.tourism.audio.client;

public final class OdiiClientException extends RuntimeException {

    private final boolean retryable;

    public OdiiClientException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public OdiiClientException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}

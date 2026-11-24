package com.yrootlab.onmaru.mapcollab.domain.exception;

public final class RateLimitedException extends MapCollabException {
    private final int retryAfterSeconds;

    public RateLimitedException(int retryAfterSeconds) {
        super("RATE_LIMITED", 429, "rate limited");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

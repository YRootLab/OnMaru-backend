package com.yrootlab.onmaru.stamp;

public final class CheckInRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    public CheckInRateLimitedException(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

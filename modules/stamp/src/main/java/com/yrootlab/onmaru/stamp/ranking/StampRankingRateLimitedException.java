package com.yrootlab.onmaru.stamp.ranking;

public final class StampRankingRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    public StampRankingRateLimitedException(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

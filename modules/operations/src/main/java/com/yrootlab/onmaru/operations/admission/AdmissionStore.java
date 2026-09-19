package com.yrootlab.onmaru.operations.admission;

import java.time.Duration;
import java.time.Instant;

public interface AdmissionStore {
    AdmissionDecision tryConsume(AdmissionScope scope, Instant windowStart, int limit, Duration retryAfter);

    AdmissionDecision tryStart(
            AdmissionScope scope,
            Instant windowStart,
            int limit,
            int activeLimit,
            Duration retryAfter,
            Duration activeRetryAfter);

    void releaseActive(AdmissionScope scope);
}

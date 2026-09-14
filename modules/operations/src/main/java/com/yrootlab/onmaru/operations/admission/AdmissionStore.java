package com.yrootlab.onmaru.operations.admission;

import java.time.Duration;
import java.time.Instant;

public interface AdmissionStore {
    AdmissionDecision tryConsume(AdmissionScope scope, Instant windowStart, int limit, Duration retryAfter);
}

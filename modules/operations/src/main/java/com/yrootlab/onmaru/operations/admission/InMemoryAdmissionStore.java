package com.yrootlab.onmaru.operations.admission;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class InMemoryAdmissionStore implements AdmissionStore {

    private final Map<AdmissionScope, AdmissionCounter> counters = new HashMap<>();

    @Override
    public synchronized AdmissionDecision tryConsume(
            AdmissionScope scope,
            Instant windowStart,
            int limit,
            Duration retryAfter
    ) {
        var counter = counters.get(scope);
        if (counter == null || !counter.windowStart().equals(windowStart)) {
            counters.put(scope, new AdmissionCounter(windowStart, 1));
            return AdmissionDecision.allow();
        }
        if (counter.consumed() >= limit) {
            return AdmissionDecision.rejected(retryAfter);
        }
        counters.put(scope, new AdmissionCounter(windowStart, counter.consumed() + 1));
        return AdmissionDecision.allow();
    }
}

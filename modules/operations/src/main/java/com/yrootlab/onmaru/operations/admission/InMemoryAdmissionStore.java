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
            counters.put(scope, new AdmissionCounter(windowStart, 1, 0));
            return AdmissionDecision.allow();
        }
        if (counter.consumed() >= limit) {
            return AdmissionDecision.rejected(retryAfter);
        }
        counters.put(scope, new AdmissionCounter(windowStart, counter.consumed() + 1, counter.activeCount()));
        return AdmissionDecision.allow();
    }

    @Override
    public synchronized AdmissionDecision tryStart(
            AdmissionScope scope,
            Instant windowStart,
            int limit,
            int activeLimit,
            Duration retryAfter,
            Duration activeRetryAfter
    ) {
        var counter = counters.get(scope);
        if (counter == null || !counter.windowStart().equals(windowStart)) {
            counters.put(scope, new AdmissionCounter(windowStart, 1, 1));
            return AdmissionDecision.allow();
        }
        if (counter.consumed() >= limit) {
            return AdmissionDecision.rejected(retryAfter);
        }
        if (counter.activeCount() >= activeLimit) {
            return AdmissionDecision.rejected(activeRetryAfter, AdmissionRejectionReason.ACTIVE_LIMIT);
        }
        counters.put(scope, new AdmissionCounter(
                windowStart,
                counter.consumed() + 1,
                counter.activeCount() + 1));
        return AdmissionDecision.allow();
    }

    @Override
    public synchronized void releaseActive(AdmissionScope scope) {
        var counter = counters.get(scope);
        if (counter == null || counter.activeCount() == 0) {
            return;
        }
        counters.put(scope, new AdmissionCounter(
                counter.windowStart(),
                counter.consumed(),
                counter.activeCount() - 1));
    }
}

package com.yrootlab.onmaru.operations.admission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class AdmissionService {

    private final AdmissionStore store;
    private final Clock clock;

    public AdmissionService(AdmissionStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public AdmissionDecision admit(AdmissionRequest request, AdmissionPolicy policy) {
        var budget = policy.budgetFor(request.operation(), request.subject().type());
        var now = clock.instant();
        var windowStart = windowStart(now, policy.window());
        var retryAfter = Duration.between(now, windowStart.plus(policy.window()));
        var scope = new AdmissionScope(request.operation(), request.subject().type(), request.subject().key());
        return store.tryConsume(scope, windowStart, budget.limit(), retryAfter);
    }

    private Instant windowStart(Instant now, Duration window) {
        long windowSeconds = window.toSeconds();
        long epochSecond = now.getEpochSecond();
        return Instant.ofEpochSecond(epochSecond - Math.floorMod(epochSecond, windowSeconds));
    }
}

package com.yrootlab.onmaru.operations.admission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

public final class AdmissionService {

    private static final Duration DAILY_WINDOW = Duration.ofDays(1);
    private static final Duration ACTIVE_RETRY_AFTER = Duration.ofSeconds(30);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AdmissionStore store;
    private final Clock clock;
    private final AdmissionObservationSink observationSink;

    public AdmissionService(AdmissionStore store, Clock clock) {
        this(store, clock, AdmissionObservationSink.NOOP);
    }

    public AdmissionService(AdmissionStore store, Clock clock, AdmissionObservationSink observationSink) {
        this.store = store;
        this.clock = clock;
        this.observationSink = observationSink == null ? AdmissionObservationSink.NOOP : observationSink;
    }

    public AdmissionDecision admit(AdmissionRequest request, AdmissionPolicy policy) {
        var budget = policy.budgetFor(request.operation(), request.subject().type());
        var window = budget.window() == null ? policy.window() : budget.window();
        var now = clock.instant();
        var windowStart = windowStart(now, window);
        var retryAfter = Duration.between(now, windowStart.plus(window));
        var scope = new AdmissionScope(request.operation(), request.subject().type(), request.subject().key());
        var decision = store.tryConsume(scope, windowStart, budget.limit(), retryAfter);
        observationSink.recordDecision(scope, decision);
        return decision;
    }

    public AdmissionDecision admitActive(AdmissionRequest request, AdmissionPolicy policy) {
        var budget = policy.budgetFor(request.operation(), request.subject().type());
        if (budget.activeLimit() <= 0) {
            return admit(request, policy);
        }
        var window = budget.window() == null ? policy.window() : budget.window();
        var now = clock.instant();
        var windowStart = windowStart(now, window);
        var retryAfter = Duration.between(now, windowStart.plus(window));
        var scope = new AdmissionScope(request.operation(), request.subject().type(), request.subject().key());
        var decision = store.tryStart(scope, windowStart, budget.limit(), budget.activeLimit(), retryAfter, ACTIVE_RETRY_AFTER);
        observationSink.recordDecision(scope, decision);
        return decision;
    }

    public void releaseActive(AdmissionRequest request, AdmissionPolicy policy) {
        policy.budgetFor(request.operation(), request.subject().type());
        var scope = new AdmissionScope(request.operation(), request.subject().type(), request.subject().key());
        store.releaseActive(scope);
        observationSink.recordRelease(scope);
    }

    private Instant windowStart(Instant now, Duration window) {
        if (DAILY_WINDOW.equals(window)) {
            return now.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
        }
        long windowSeconds = window.toSeconds();
        long epochSecond = now.getEpochSecond();
        return Instant.ofEpochSecond(epochSecond - Math.floorMod(epochSecond, windowSeconds));
    }
}

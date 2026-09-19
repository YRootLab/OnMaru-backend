package com.yrootlab.onmaru.operations.retention;

import java.time.Clock;
import java.util.Objects;

public final class RetentionCleanupService {

    private final RetentionCleanupStore store;
    private final Clock clock;
    private final RetentionCleanupObserver observer;

    public RetentionCleanupService(
            RetentionCleanupStore store,
            Clock clock,
            RetentionCleanupObserver observer
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observer = observer == null ? RetentionCleanupObserver.NOOP : observer;
    }

    public RetentionCleanupResult runOnce(RetentionCleanupPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        var result = store.cleanup(policy, clock.instant());
        observer.record(result);
        return result;
    }
}

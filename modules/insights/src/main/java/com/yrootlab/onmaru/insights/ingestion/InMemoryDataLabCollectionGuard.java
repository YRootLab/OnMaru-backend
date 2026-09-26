package com.yrootlab.onmaru.insights.ingestion;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InMemoryDataLabCollectionGuard implements DataLabCollectionGuard {

    private final AtomicBoolean held = new AtomicBoolean();

    @Override
    public Optional<Lease> tryAcquire() {
        if (!held.compareAndSet(false, true)) {
            return Optional.empty();
        }
        return Optional.of(() -> held.set(false));
    }
}

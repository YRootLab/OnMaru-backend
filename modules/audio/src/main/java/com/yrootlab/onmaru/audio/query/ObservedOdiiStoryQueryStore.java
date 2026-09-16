package com.yrootlab.onmaru.audio.query;

import java.util.Objects;

public final class ObservedOdiiStoryQueryStore implements OdiiStoryQueryStore {

    private final OdiiStoryQueryStore delegate;
    private final OdiiStoryQueryObserver observer;

    public ObservedOdiiStoryQueryStore(OdiiStoryQueryStore delegate, OdiiStoryQueryObserver observer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public OdiiActiveSnapshot activeSnapshot() {
        try {
            OdiiActiveSnapshot snapshot = delegate.activeSnapshot();
            observer.activeRevisionAvailable(snapshot);
            return snapshot;
        } catch (RuntimeException exception) {
            observer.activeRevisionUnavailable(exception);
            throw exception;
        }
    }
}

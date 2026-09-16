package com.yrootlab.onmaru.audio.query;

public final class InMemoryOdiiStoryQueryStore implements OdiiStoryQueryStore {

    private volatile OdiiActiveSnapshot snapshot;
    private volatile boolean unavailable;

    @Override
    public OdiiActiveSnapshot activeSnapshot() {
        if (unavailable || snapshot == null) {
            throw new OdiiStoryUnavailableException();
        }
        return snapshot;
    }

    public void replaceActive(OdiiActiveSnapshot replacement) {
        snapshot = replacement;
        unavailable = false;
    }

    public void markUnavailable() {
        unavailable = true;
    }

    public void clear() {
        snapshot = null;
        unavailable = false;
    }
}

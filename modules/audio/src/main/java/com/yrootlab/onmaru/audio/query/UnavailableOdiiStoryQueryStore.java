package com.yrootlab.onmaru.audio.query;

public final class UnavailableOdiiStoryQueryStore implements OdiiStoryQueryStore {

    @Override
    public OdiiActiveSnapshot activeSnapshot() {
        throw new OdiiStoryUnavailableException();
    }
}

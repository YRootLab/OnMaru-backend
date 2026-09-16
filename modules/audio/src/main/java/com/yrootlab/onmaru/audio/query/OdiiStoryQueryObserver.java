package com.yrootlab.onmaru.audio.query;

public interface OdiiStoryQueryObserver {

    OdiiStoryQueryObserver NOOP = new OdiiStoryQueryObserver() {
    };

    default void activeRevisionAvailable(OdiiActiveSnapshot snapshot) {
    }

    default void activeRevisionUnavailable(RuntimeException exception) {
    }
}

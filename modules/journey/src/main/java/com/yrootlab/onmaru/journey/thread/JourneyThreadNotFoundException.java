package com.yrootlab.onmaru.journey.thread;

public final class JourneyThreadNotFoundException extends RuntimeException {

    public JourneyThreadNotFoundException() {
        super("Journey thread not found");
    }

    public JourneyThreadNotFoundException(String message) {
        super(message);
    }
}

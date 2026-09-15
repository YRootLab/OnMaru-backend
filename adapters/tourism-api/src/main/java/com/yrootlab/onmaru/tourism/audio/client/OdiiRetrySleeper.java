package com.yrootlab.onmaru.tourism.audio.client;

@FunctionalInterface
public interface OdiiRetrySleeper {

    void sleep(long seconds) throws InterruptedException;

    static OdiiRetrySleeper threadSleep() {
        return seconds -> Thread.sleep(seconds * 1_000L);
    }
}

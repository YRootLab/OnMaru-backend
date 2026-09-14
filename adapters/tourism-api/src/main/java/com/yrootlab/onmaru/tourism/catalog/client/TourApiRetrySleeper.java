package com.yrootlab.onmaru.tourism.catalog.client;

@FunctionalInterface
public interface TourApiRetrySleeper {
    void sleep(long seconds) throws InterruptedException;

    static TourApiRetrySleeper threadSleep() {
        return seconds -> Thread.sleep(seconds * 1_000);
    }
}

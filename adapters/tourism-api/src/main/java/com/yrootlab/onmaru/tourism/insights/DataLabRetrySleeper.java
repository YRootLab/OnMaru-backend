package com.yrootlab.onmaru.tourism.insights;

@FunctionalInterface
public interface DataLabRetrySleeper {

    void sleep(long seconds) throws InterruptedException;

    static DataLabRetrySleeper threadSleep() {
        return seconds -> Thread.sleep(seconds * 1000L);
    }
}

package com.yrootlab.onmaru.tourism.audio.client;

import java.time.Duration;

public record OdiiClientProperties(
        Duration connectTimeout,
        Duration attemptTimeout,
        Duration pageTimeout,
        int retryCount
) {

    public OdiiClientProperties {
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(attemptTimeout, "attemptTimeout");
        requirePositive(pageTimeout, "pageTimeout");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must be non-negative");
        }
    }

    public static OdiiClientProperties defaults() {
        return new OdiiClientProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofSeconds(12),
                2
        );
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || !value.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

package com.yrootlab.onmaru.tourism.catalog.client;

import java.time.Duration;

public record TourApiClientProperties(
        Duration connectTimeout,
        Duration attemptTimeout,
        Duration pageTimeout,
        int retryCount
) {
    public TourApiClientProperties {
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(attemptTimeout, "attemptTimeout");
        requirePositive(pageTimeout, "pageTimeout");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must be greater than or equal to 0");
        }
    }

    public static TourApiClientProperties defaults() {
        return new TourApiClientProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofSeconds(12),
                2
        );
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || !duration.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

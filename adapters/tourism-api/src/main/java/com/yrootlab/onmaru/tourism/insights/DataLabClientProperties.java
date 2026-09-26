package com.yrootlab.onmaru.tourism.insights;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Bounded HTTP policy for one DataLab page request. */
public record DataLabClientProperties(
        URI baseUri,
        String serviceKey,
        String mobileApp,
        Duration connectTimeout,
        Duration attemptTimeout,
        Duration pageTimeout,
        int retryCount
) {
    public DataLabClientProperties {
        Objects.requireNonNull(baseUri, "baseUri must not be null");
        requireNonBlank(serviceKey, "serviceKey");
        requireNonBlank(mobileApp, "mobileApp");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(attemptTimeout, "attemptTimeout");
        requirePositive(pageTimeout, "pageTimeout");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must be greater than or equal to 0");
        }
    }

    public static DataLabClientProperties defaults(URI baseUri, String serviceKey, String mobileApp) {
        return new DataLabClientProperties(
                baseUri, serviceKey, mobileApp,
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(12), 2);
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || !value.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

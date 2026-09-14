package com.yrootlab.onmaru.tourism.catalog.client;

public record TourApiError(
        String operation,
        TourApiOutcomeKind kind,
        String providerCode,
        String providerMessage,
        boolean retryable,
        Long retryAfterSeconds
) {
}

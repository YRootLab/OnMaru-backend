package com.yrootlab.onmaru.tourism.catalog.client;

public enum TourApiOutcomeKind {
    SUCCESS,
    RATE_LIMITED,
    AUTH_OR_PERMISSION_ERROR,
    PROVIDER_PARAMETER_ERROR,
    UPSTREAM_TIMEOUT_OR_GATEWAY_ERROR,
    SCHEMA_DRIFT
}

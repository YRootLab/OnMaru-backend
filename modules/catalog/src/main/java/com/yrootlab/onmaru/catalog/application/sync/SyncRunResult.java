package com.yrootlab.onmaru.catalog.application.sync;

public enum SyncRunResult {
    SUCCEEDED,
    FAILED_RETRYABLE,
    LEASE_LOST
}

package com.yrootlab.onmaru.operations.retention;

public interface RetentionCleanupObserver {

    RetentionCleanupObserver NOOP = result -> {
    };

    void record(RetentionCleanupResult result);
}

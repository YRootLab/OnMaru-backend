package com.yrootlab.onmaru.web.exploration;

import java.time.Duration;

final class ExplorationQuotaExceededException extends RuntimeException {

    private final Duration retryAfter;

    ExplorationQuotaExceededException(Duration retryAfter) {
        this.retryAfter = retryAfter;
    }

    Duration retryAfter() {
        return retryAfter;
    }
}

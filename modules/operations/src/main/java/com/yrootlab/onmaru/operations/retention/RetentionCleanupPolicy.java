package com.yrootlab.onmaru.operations.retention;

import java.time.Duration;

public record RetentionCleanupPolicy(
        int batchSize,
        Duration guestTtl,
        Duration sessionTtl,
        Duration runTtl,
        Duration proposalTtl,
        Duration inactiveRevisionTtl
) {

    private static final int DEFAULT_BATCH_SIZE = 500;

    public RetentionCleanupPolicy {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        requirePositive(guestTtl, "guestTtl");
        requirePositive(sessionTtl, "sessionTtl");
        requirePositive(runTtl, "runTtl");
        requirePositive(proposalTtl, "proposalTtl");
        requirePositive(inactiveRevisionTtl, "inactiveRevisionTtl");
    }

    public static RetentionCleanupPolicy defaults() {
        return new RetentionCleanupPolicy(
                DEFAULT_BATCH_SIZE,
                Duration.ofDays(30),
                Duration.ofDays(1),
                Duration.ofHours(1),
                Duration.ofDays(7),
                Duration.ofDays(14));
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

package com.yrootlab.onmaru.operations.retention;

import java.time.Instant;

public interface RetentionCleanupStore {

    RetentionCleanupResult cleanup(RetentionCleanupPolicy policy, Instant now);
}

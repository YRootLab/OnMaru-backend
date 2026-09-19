package com.yrootlab.onmaru.catalog.application.sync;

import java.time.Instant;

public record ScheduledSyncRun(String dataset, Instant scheduledFor, int attempt) {
}

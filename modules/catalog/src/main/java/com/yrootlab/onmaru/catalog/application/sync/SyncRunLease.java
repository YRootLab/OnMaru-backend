package com.yrootlab.onmaru.catalog.application.sync;

import java.util.UUID;

public record SyncRunLease(UUID runId, String dataset, String ownerToken, int generation) {
}

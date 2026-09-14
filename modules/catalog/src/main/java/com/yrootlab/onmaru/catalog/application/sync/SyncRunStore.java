package com.yrootlab.onmaru.catalog.application.sync;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SyncRunStore {

    Optional<SyncRunLease> acquireLease(String dataset, String ownerToken, Instant now);

    Optional<SyncCheckpoint> checkpoint(UUID runId, String partitionKey);

    boolean saveCheckpointIfLeaseHeld(SyncRunLease lease, SyncCheckpoint checkpoint);

    boolean finishIfLeaseHeld(SyncRunLease lease, SyncRunStatus status, Instant now);
}

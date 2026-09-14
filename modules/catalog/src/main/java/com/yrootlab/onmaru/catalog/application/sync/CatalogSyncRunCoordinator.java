package com.yrootlab.onmaru.catalog.application.sync;

import java.time.Clock;

public final class CatalogSyncRunCoordinator {

    private static final String DEFAULT_PARTITION = "default";

    private final SyncRunStore store;
    private final SyncPageSource source;
    private final Clock clock;
    private final String ownerToken;

    public CatalogSyncRunCoordinator(SyncRunStore store, SyncPageSource source, Clock clock, String ownerToken) {
        this.store = store;
        this.source = source;
        this.clock = clock;
        this.ownerToken = ownerToken;
    }

    public SyncRunResult runDataset(String dataset) {
        var lease = store.acquireLease(dataset, ownerToken, clock.instant());
        if (lease.isEmpty()) {
            return SyncRunResult.LEASE_LOST;
        }

        var currentLease = lease.orElseThrow();
        var nextPage = store.checkpoint(currentLease.runId(), DEFAULT_PARTITION)
                .map(SyncCheckpoint::nextPage)
                .orElse(1);
        try {
            while (true) {
                var page = source.fetch(dataset, nextPage);
                var checkpoint = new SyncCheckpoint(
                        currentLease.runId(),
                        DEFAULT_PARTITION,
                        page.page() + 1,
                        page.seenCount(),
                        null,
                        null
                );
                if (!store.saveCheckpointIfLeaseHeld(currentLease, checkpoint)) {
                    return SyncRunResult.LEASE_LOST;
                }
                if (page.lastPage()) {
                    return store.finishIfLeaseHeld(currentLease, SyncRunStatus.SUCCEEDED, clock.instant())
                            ? SyncRunResult.SUCCEEDED
                            : SyncRunResult.LEASE_LOST;
                }
                nextPage++;
            }
        } catch (SyncPageException exception) {
            store.finishIfLeaseHeld(currentLease, SyncRunStatus.FAILED, clock.instant());
            return SyncRunResult.FAILED_RETRYABLE;
        }
    }
}

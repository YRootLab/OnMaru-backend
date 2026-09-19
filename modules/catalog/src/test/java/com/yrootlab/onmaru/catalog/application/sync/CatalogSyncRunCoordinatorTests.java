package com.yrootlab.onmaru.catalog.application.sync;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSyncRunCoordinatorTests {

    @Test
    void resumesFromCheckpointAfterPageFailure() {
        var runId = UUID.randomUUID();
        var store = new InMemoryRunStore(runId);
        var source = new FailingOnceSource();
        var coordinator = new CatalogSyncRunCoordinator(
                store,
                source,
                Clock.fixed(Instant.parse("2026-09-15T03:00:00Z"), ZoneOffset.UTC),
                "worker-a"
        );

        var first = coordinator.runDataset("kto-korean-tour");
        var second = coordinator.runDataset("kto-korean-tour");

        assertThat(first).isEqualTo(SyncRunResult.FAILED_RETRYABLE);
        assertThat(second).isEqualTo(SyncRunResult.SUCCEEDED);
        assertThat(source.requestedPages).containsExactly(1, 2, 2, 3);
        assertThat(store.checkpoint("default").orElseThrow().nextPage()).isEqualTo(4);
        assertThat(store.status).isEqualTo(SyncRunStatus.SUCCEEDED);
    }

    @Test
    void staleLeaseOwnerCannotWriteLateCheckpoint() {
        var runId = UUID.randomUUID();
        var store = new InMemoryRunStore(runId);
        var coordinator = new CatalogSyncRunCoordinator(
                store,
                new LeaseLossSource(store),
                Clock.fixed(Instant.parse("2026-09-15T03:00:00Z"), ZoneOffset.UTC),
                "worker-a"
        );

        var result = coordinator.runDataset("kto-korean-tour");

        assertThat(result).isEqualTo(SyncRunResult.LEASE_LOST);
        assertThat(store.checkpoint("default")).isEmpty();
    }

    static final class InMemoryRunStore implements SyncRunStore {

        private final UUID runId;
        private final List<SyncCheckpoint> checkpoints = new ArrayList<>();
        private String leaseOwner = "worker-a";
        private int generation = 1;
        private SyncRunStatus status = SyncRunStatus.RUNNING;

        InMemoryRunStore(UUID runId) {
            this.runId = runId;
        }

        @Override
        public Optional<SyncRunLease> acquireLease(String dataset, String ownerToken, Instant now) {
            if (!leaseOwner.equals(ownerToken)) {
                return Optional.empty();
            }
            return Optional.of(new SyncRunLease(runId, dataset, ownerToken, generation));
        }

        @Override
        public Optional<SyncCheckpoint> checkpoint(UUID runId, String partitionKey) {
            return checkpoints.stream()
                    .filter(checkpoint -> checkpoint.runId().equals(runId))
                    .filter(checkpoint -> checkpoint.partitionKey().equals(partitionKey))
                    .findFirst();
        }

        @Override
        public boolean saveCheckpointIfLeaseHeld(SyncRunLease lease, SyncCheckpoint checkpoint) {
            if (!isCurrent(lease)) {
                return false;
            }
            checkpoints.removeIf(existing -> existing.runId().equals(checkpoint.runId())
                    && existing.partitionKey().equals(checkpoint.partitionKey()));
            checkpoints.add(checkpoint);
            return true;
        }

        @Override
        public boolean finishIfLeaseHeld(SyncRunLease lease, SyncRunStatus status, Instant now) {
            if (!isCurrent(lease)) {
                return false;
            }
            this.status = status;
            return true;
        }

        void loseLeaseTo(String ownerToken) {
            leaseOwner = ownerToken;
            generation++;
        }

        Optional<SyncCheckpoint> checkpoint(String partitionKey) {
            return checkpoint(runId, partitionKey);
        }

        private boolean isCurrent(SyncRunLease lease) {
            var current = lease.ownerToken().equals(leaseOwner) && lease.generation() == generation;
            return current;
        }
    }

    static final class FailingOnceSource implements SyncPageSource {

        private final List<Integer> requestedPages = new ArrayList<>();
        private boolean failedPageTwo;

        @Override
        public SyncPage fetch(String dataset, int page) {
            requestedPages.add(page);
            if (page == 2 && !failedPageTwo) {
                failedPageTwo = true;
                throw new SyncPageException("temporary provider failure");
            }
            return new SyncPage(page, page == 3, 100L + page);
        }
    }

    static final class LeaseLossSource implements SyncPageSource {

        private final InMemoryRunStore store;

        LeaseLossSource(InMemoryRunStore store) {
            this.store = store;
        }

        @Override
        public SyncPage fetch(String dataset, int page) {
            store.loseLeaseTo("worker-b");
            return new SyncPage(page, false, 10);
        }
    }
}

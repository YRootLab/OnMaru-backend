package com.yrootlab.onmaru.scheduling.audio;

import java.time.Instant;
import java.util.UUID;

/** Scheduler history is separate from publication/lease transactions. */
public interface OdiiSyncRunStore {
    void save(Run run);

    record Run(UUID id, String dataset, String triggerSource, Instant startedAt, Instant finishedAt,
               String lifecycleStatus, String failurePhase, String errorCode, UUID revisionId,
               Integer leaseGeneration, long fetchedCount, long mappedCount, long stagedCount,
               long publishedCount, long tombstoneCount) {
        public Run(UUID id, String dataset, String triggerSource, Instant startedAt, Instant finishedAt,
                   String lifecycleStatus, String failurePhase, String errorCode, UUID revisionId,
                   Integer leaseGeneration, long stagedCount, long tombstoneCount) {
            this(id, dataset, triggerSource, startedAt, finishedAt, lifecycleStatus, failurePhase,
                    errorCode, revisionId, leaseGeneration, 0, 0, stagedCount,
                    "COMPLETED".equals(lifecycleStatus) ? stagedCount : 0, tombstoneCount);
        }
    }
}

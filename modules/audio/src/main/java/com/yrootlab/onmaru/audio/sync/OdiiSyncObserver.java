package com.yrootlab.onmaru.audio.sync;

import java.util.UUID;

public interface OdiiSyncObserver {

    OdiiSyncObserver NOOP = new OdiiSyncObserver() {
    };

    default void started(String dataset, UUID expectedRevisionId) {
    }

    default void completed(String dataset, OdiiSyncResult result) {
    }

    default void failed(String dataset, UUID revisionId, String failureCode) {
    }

    default void phaseFailed(String dataset, UUID revisionId, String phase, String failureCode) {
        failed(dataset, revisionId, failureCode);
    }

    default void staged(String dataset, UUID revisionId, long itemCount) {
    }

    /** Committed stage metadata, observed before subsequent count reads or publication. */
    default void stageCompleted(String dataset, UUID revisionId, long tombstoneCount) {
    }

    /** Incremental raw story count, including duplicates and curation exclusions. */
    default void fetched(String dataset, UUID revisionId, long count) {
    }

    /** Incremental successfully mapped, curated and deduplicated story count. */
    default void mapped(String dataset, UUID revisionId, long count) {
    }

    default void published(String dataset, UUID revisionId, OdiiSyncResult result) {
    }
}

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

    default void published(String dataset, UUID revisionId, OdiiSyncResult result) {
    }
}

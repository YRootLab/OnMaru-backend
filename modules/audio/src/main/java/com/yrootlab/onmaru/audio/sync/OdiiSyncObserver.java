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
}

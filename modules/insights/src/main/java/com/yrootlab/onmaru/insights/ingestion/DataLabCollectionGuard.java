package com.yrootlab.onmaru.insights.ingestion;

import java.util.Optional;

/** Coordinates a complete DataLab fetch-and-publish operation across every trigger. */
public interface DataLabCollectionGuard {

    Optional<Lease> tryAcquire();

    interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}

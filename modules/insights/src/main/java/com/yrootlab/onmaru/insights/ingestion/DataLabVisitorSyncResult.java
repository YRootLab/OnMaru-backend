package com.yrootlab.onmaru.insights.ingestion;

import java.util.Map;

public record DataLabVisitorSyncResult(
        boolean published,
        int observationCount,
        int skippedCount,
        int quarantinedCount,
        Map<DataLabCollectionReason, Long> reasons) {

    public DataLabVisitorSyncResult {
        if (observationCount < 0 || skippedCount < 0 || quarantinedCount < 0) {
            throw new IllegalArgumentException("sync result counts must not be negative");
        }
        reasons = Map.copyOf(reasons);
    }
}

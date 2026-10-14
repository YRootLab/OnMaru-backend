package com.yrootlab.onmaru.scheduling.catalog.detail;

public record DetailCollectionReport(int okCount, int failedCount, String stoppedBy, String skippedReason) {

    public static DetailCollectionReport skipped(String reason) {
        return new DetailCollectionReport(0, 0, null, reason);
    }

    public boolean ran() {
        return skippedReason == null;
    }
}

package com.yrootlab.onmaru.catalog.application.publication;

public record StageValidation(
        boolean ready,
        long rowCount,
        boolean emptyFullSyncReviewed,
        String failureCode
) {
    public static StageValidation ready(long rowCount, boolean emptyFullSyncReviewed) {
        return new StageValidation(true, rowCount, emptyFullSyncReviewed, null);
    }

    public static StageValidation incomplete(String failureCode) {
        return new StageValidation(false, 0, false, failureCode);
    }
}

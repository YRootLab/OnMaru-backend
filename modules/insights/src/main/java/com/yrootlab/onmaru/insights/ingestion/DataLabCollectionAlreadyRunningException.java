package com.yrootlab.onmaru.insights.ingestion;

public final class DataLabCollectionAlreadyRunningException extends RuntimeException {

    public DataLabCollectionAlreadyRunningException() {
        super("DataLab collection is already running");
    }
}

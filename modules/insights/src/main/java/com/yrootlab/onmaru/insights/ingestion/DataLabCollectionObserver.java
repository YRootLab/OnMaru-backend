package com.yrootlab.onmaru.insights.ingestion;

@FunctionalInterface
public interface DataLabCollectionObserver {

    DataLabCollectionObserver NOOP = (outcome, reason) -> { };

    void record(DataLabCollectionOutcome outcome, DataLabCollectionReason reason);
}

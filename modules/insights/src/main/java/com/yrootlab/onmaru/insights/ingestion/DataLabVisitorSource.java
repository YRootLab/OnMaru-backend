package com.yrootlab.onmaru.insights.ingestion;

/** External DataLab boundary with explicit skip and quarantine results. */
@FunctionalInterface
public interface DataLabVisitorSource {

    DataLabVisitorFetchResult fetchDailyVisitorObservations();
}

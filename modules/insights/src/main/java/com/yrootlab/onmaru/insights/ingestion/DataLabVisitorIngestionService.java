package com.yrootlab.onmaru.insights.ingestion;

import java.util.Objects;

/** Coordinates fetch-before-replace so a failed fetch never changes the active revision. */
public final class DataLabVisitorIngestionService {

    private final DataLabVisitorSource source;
    private final DataLabVisitorRevisionWriter revisionWriter;

    public DataLabVisitorIngestionService(
            DataLabVisitorSource source,
            DataLabVisitorRevisionWriter revisionWriter) {
        this.source = Objects.requireNonNull(source);
        this.revisionWriter = Objects.requireNonNull(revisionWriter);
    }

    public void sync() {
        DataLabVisitorFetchResult result = source.fetchDailyVisitorObservations();
        if (result.publishable()) {
            revisionWriter.replaceActive(result.observations());
        }
    }
}

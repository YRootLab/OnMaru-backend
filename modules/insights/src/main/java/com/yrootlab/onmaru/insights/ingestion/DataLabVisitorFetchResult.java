package com.yrootlab.onmaru.insights.ingestion;

import com.yrootlab.onmaru.insights.observation.VisitorObservation;

import java.util.List;

public record DataLabVisitorFetchResult(
        List<VisitorObservation> observations,
        List<DataLabCollectionExclusion> exclusions,
        boolean quarantined) {

    public DataLabVisitorFetchResult {
        observations = List.copyOf(observations);
        exclusions = List.copyOf(exclusions);
        if (quarantined && !observations.isEmpty()) {
            throw new IllegalArgumentException("quarantined result must not expose publishable observations");
        }
    }

    public boolean publishable() {
        return !quarantined && !observations.isEmpty();
    }
}

package com.yrootlab.onmaru.insights.observation;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable boundary for regional visitor observations. */
public interface VisitorObservationStore {

    void save(UUID revisionId, VisitorObservation observation);

    /** Returns only real values from the active DataLab revision; missing values stay absent. */
    Map<String, Long> findLatestCompleteByRegionCodes(Set<String> regionCodes);
}

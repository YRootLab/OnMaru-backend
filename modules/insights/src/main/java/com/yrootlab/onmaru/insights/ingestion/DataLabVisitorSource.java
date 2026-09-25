package com.yrootlab.onmaru.insights.ingestion;

import com.yrootlab.onmaru.insights.observation.VisitorObservation;

import java.util.List;

/** External DataLab boundary. Implementations must throw when a fetch cannot complete. */
@FunctionalInterface
public interface DataLabVisitorSource {

    List<VisitorObservation> fetchDailyVisitorObservations();
}

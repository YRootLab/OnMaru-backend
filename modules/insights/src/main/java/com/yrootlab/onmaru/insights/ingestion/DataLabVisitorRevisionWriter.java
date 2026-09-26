package com.yrootlab.onmaru.insights.ingestion;

import com.yrootlab.onmaru.insights.observation.VisitorObservation;

import java.util.List;

/**
 * Atomically writes a verified DataLab batch and activates its dataset revision.
 * Implementations must leave the prior active revision intact if the replacement fails.
 */
@FunctionalInterface
public interface DataLabVisitorRevisionWriter {

    void replaceActive(List<VisitorObservation> observations);
}

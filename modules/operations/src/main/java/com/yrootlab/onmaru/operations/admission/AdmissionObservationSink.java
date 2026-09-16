package com.yrootlab.onmaru.operations.admission;

public interface AdmissionObservationSink {

    AdmissionObservationSink NOOP = new AdmissionObservationSink() {
    };

    default void recordDecision(AdmissionScope scope, AdmissionDecision decision) {
    }

    default void recordRelease(AdmissionScope scope) {
    }
}

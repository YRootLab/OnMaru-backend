package com.yrootlab.onmaru.journey.observation;

public interface RunObservationStore extends RunObservationQueryPort {

    void save(RunObservation observation);
}

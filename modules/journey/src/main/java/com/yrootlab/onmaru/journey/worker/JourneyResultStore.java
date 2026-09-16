package com.yrootlab.onmaru.journey.worker;

@FunctionalInterface
public interface JourneyResultStore {

    PersistJourneyResultResult persist(PersistJourneyResultCommand command);
}

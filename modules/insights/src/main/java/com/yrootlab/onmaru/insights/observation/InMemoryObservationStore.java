package com.yrootlab.onmaru.insights.observation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryObservationStore {

    private final List<VisitorObservation> visitorObservations = new CopyOnWriteArrayList<>();
    private final List<ConcentrationObservation> concentrationObservations = new CopyOnWriteArrayList<>();

    public void save(VisitorObservation observation) {
        visitorObservations.add(observation);
    }

    public void save(ConcentrationObservation observation) {
        concentrationObservations.add(observation);
    }

    public List<VisitorObservation> visitorObservations() {
        return List.copyOf(visitorObservations);
    }

    public List<ConcentrationObservation> concentrationObservations() {
        return List.copyOf(concentrationObservations);
    }
}

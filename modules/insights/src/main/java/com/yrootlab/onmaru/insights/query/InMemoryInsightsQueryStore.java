package com.yrootlab.onmaru.insights.query;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryInsightsQueryStore implements InsightsQueryStore {

    private final List<Observation> observations = new CopyOnWriteArrayList<>();
    private final List<HeatSpot> heatSpots = new CopyOnWriteArrayList<>();

    public void save(Observation observation) {
        observations.add(observation);
    }

    public void save(HeatSpot heatSpot) {
        heatSpots.add(heatSpot);
    }

    public void clear() {
        observations.clear();
        heatSpots.clear();
    }

    @Override
    public List<Observation> observations() {
        return List.copyOf(observations);
    }

    @Override
    public List<HeatSpot> heatSpots() {
        return List.copyOf(heatSpots);
    }
}

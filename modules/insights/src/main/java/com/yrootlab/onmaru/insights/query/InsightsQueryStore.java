package com.yrootlab.onmaru.insights.query;

import java.util.List;

public interface InsightsQueryStore {
    List<Observation> observations();
    List<HeatSpot> heatSpots();
}

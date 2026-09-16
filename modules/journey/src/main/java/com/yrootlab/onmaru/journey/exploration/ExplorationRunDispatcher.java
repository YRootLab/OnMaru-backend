package com.yrootlab.onmaru.journey.exploration;

@FunctionalInterface
public interface ExplorationRunDispatcher {

    void dispatch(ExplorationRunRequest request);
}

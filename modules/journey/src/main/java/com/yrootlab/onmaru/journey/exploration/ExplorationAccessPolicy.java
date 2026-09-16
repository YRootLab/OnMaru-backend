package com.yrootlab.onmaru.journey.exploration;

@FunctionalInterface
public interface ExplorationAccessPolicy {

    boolean canAccess(ExplorationActor actor, ExplorationState state);
}

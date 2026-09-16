package com.yrootlab.onmaru.journey.saved.odii;

@FunctionalInterface
public interface OdiiStorySaveEligibility {
    boolean isSaveable(String storyId);
}

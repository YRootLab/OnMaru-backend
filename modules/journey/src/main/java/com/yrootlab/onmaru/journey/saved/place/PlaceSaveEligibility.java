package com.yrootlab.onmaru.journey.saved.place;

@FunctionalInterface
public interface PlaceSaveEligibility {

    boolean isSaveable(String placeId);
}

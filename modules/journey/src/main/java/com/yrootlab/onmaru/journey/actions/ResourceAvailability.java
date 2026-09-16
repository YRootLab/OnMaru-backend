package com.yrootlab.onmaru.journey.actions;

@FunctionalInterface
public interface ResourceAvailability {
    boolean isPublic(ResourceRef resourceRef);
}

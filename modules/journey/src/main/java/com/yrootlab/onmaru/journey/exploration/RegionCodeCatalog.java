package com.yrootlab.onmaru.journey.exploration;

/**
 * Decides whether an explicitly requested region code exists in the active catalog revision.
 * Format is checked by the service; existence is the port's job.
 */
@FunctionalInterface
public interface RegionCodeCatalog {

    RegionCodeCatalog ACCEPT_ALL = regionCode -> true;

    boolean isKnown(String regionCode);
}

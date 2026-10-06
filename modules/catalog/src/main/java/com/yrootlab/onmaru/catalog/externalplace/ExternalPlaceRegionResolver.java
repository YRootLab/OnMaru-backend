package com.yrootlab.onmaru.catalog.externalplace;

@FunctionalInterface
public interface ExternalPlaceRegionResolver {

    String resolve(ExternalPlaceCandidate candidate);
}

package com.yrootlab.onmaru.catalog.externalplace;

public interface ExternalPlaceRegistry {

    ExternalPlace resolveOrCreate(ExternalPlaceCandidate candidate, String regionCode);
}

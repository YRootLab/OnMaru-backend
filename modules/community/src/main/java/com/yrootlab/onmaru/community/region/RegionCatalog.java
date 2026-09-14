package com.yrootlab.onmaru.community.region;

import java.util.List;
import java.util.Optional;

public interface RegionCatalog {

    Optional<String> activeRevisionId();

    List<RegionProjection> regions();

    List<RegionProjection> resolve(double latitude, double longitude);
}

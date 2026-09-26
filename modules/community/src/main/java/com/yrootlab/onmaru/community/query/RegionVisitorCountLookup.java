package com.yrootlab.onmaru.community.query;

import java.util.Map;
import java.util.Set;

/**
 * Read boundary for the latest usable visitor observation per region.
 * Missing keys mean that no usable observation is available for that region.
 */
@FunctionalInterface
public interface RegionVisitorCountLookup {

    Map<String, Long> findLatestVisitorCounts(Set<String> regionCodes);
}

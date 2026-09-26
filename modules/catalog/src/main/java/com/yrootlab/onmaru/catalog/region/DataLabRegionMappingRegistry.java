package com.yrootlab.onmaru.catalog.region;

import java.time.LocalDate;
import java.util.List;

@FunctionalInterface
public interface DataLabRegionMappingRegistry {

    List<DataLabRegionMapping> findCurrent(LocalDate asOf);
}

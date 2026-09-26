package com.yrootlab.onmaru.catalog.region;

import java.time.LocalDate;
import java.util.List;

/** Read boundary for provider dataset codes valid for active Catalog regions. */
public interface CatalogRegionSourceCodeLookup {

    List<CatalogRegionSourceCode> findCurrent(String provider, String dataset, LocalDate asOf);
}

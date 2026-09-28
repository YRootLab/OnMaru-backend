package com.yrootlab.onmaru.tourism.catalog.sync;

import com.yrootlab.onmaru.tourism.catalog.client.TourApiParseResult;

import java.io.IOException;
import java.net.URI;

@FunctionalInterface
public interface TourApiPageFetcher {
    TourApiParseResult fetch(String operation, URI uri) throws IOException, InterruptedException;
}

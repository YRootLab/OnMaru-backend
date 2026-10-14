package com.yrootlab.onmaru.scheduling.catalog.detail;

@FunctionalInterface
public interface DetailFetcher {
    DetailFetchResult fetch(DetailCollectionTarget target);
}

package com.yrootlab.onmaru.catalog.application.sync;

public interface SyncPageSource {

    SyncPage fetch(String dataset, int page);
}

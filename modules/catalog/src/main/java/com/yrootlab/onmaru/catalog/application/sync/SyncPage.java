package com.yrootlab.onmaru.catalog.application.sync;

public record SyncPage(int page, boolean lastPage, long seenCount) {
}

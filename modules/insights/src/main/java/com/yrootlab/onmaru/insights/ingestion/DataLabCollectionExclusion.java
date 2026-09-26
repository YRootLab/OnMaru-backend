package com.yrootlab.onmaru.insights.ingestion;

import java.util.Objects;

public record DataLabCollectionExclusion(String internalRegionCode, DataLabCollectionReason reason) {

    public DataLabCollectionExclusion {
        reason = Objects.requireNonNull(reason, "reason must not be null");
    }
}

package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.insights.ingestion.DataLabCollectionReason;
import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorSyncResult;

import java.util.Map;

record DataLabOperationsResponse(
        String buildGitSha,
        boolean published,
        int observationCount,
        int skippedCount,
        int quarantinedCount,
        Map<DataLabCollectionReason, Long> reasons) {

    DataLabOperationsResponse {
        reasons = Map.copyOf(reasons);
    }

    static DataLabOperationsResponse from(String buildGitSha, DataLabVisitorSyncResult result) {
        return new DataLabOperationsResponse(
                buildGitSha,
                result.published(),
                result.observationCount(),
                result.skippedCount(),
                result.quarantinedCount(),
                result.reasons());
    }
}

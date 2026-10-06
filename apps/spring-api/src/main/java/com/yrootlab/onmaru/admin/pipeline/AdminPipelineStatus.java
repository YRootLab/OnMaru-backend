package com.yrootlab.onmaru.admin.pipeline;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonInclude;

public record AdminPipelineStatus(
        String schemaVersion,
        String dataset,
        String status,
        Instant lastSuccessAt,
        long failureCount,
        long cumulativeFailureRunCount,
        @JsonInclude(JsonInclude.Include.ALWAYS) AdminPipelineRun lastRun,
        Object contentStats,
        Object apiUsage) {

    public AdminPipelineStatus(String dataset, String status, Instant lastSuccessAt, long failureCount) {
        this("1.1", dataset, status, lastSuccessAt, failureCount, failureCount, null, null, null);
    }

    public static AdminPipelineStatus from(
            String dataset, String status, Instant lastSuccessAt,
            long cumulativeFailureRunCount, AdminPipelineRun lastRun) {
        long latestFailureCount = lastRun == null ? 0 : lastRun.failureCount();
        return new AdminPipelineStatus("1.1", dataset, status, lastSuccessAt, latestFailureCount,
                cumulativeFailureRunCount, lastRun, null, null);
    }
}

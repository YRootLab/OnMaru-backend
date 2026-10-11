package com.yrootlab.onmaru.admin.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

public record AdminPipelineRun(
        UUID runId,
        String dataset,
        String scope,
        String status,
        @JsonInclude(JsonInclude.Include.ALWAYS) AdminPipelineProgress progress,
        @JsonInclude(JsonInclude.Include.ALWAYS) Instant startedAt,
        @JsonInclude(JsonInclude.Include.ALWAYS) Instant finishedAt,
        long failureCount) {
    public Long durationSeconds() {
        return startedAt == null || finishedAt == null ? null : Duration.between(startedAt, finishedAt).getSeconds();
    }
}

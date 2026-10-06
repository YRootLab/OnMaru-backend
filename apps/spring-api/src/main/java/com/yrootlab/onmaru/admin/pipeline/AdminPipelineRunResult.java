package com.yrootlab.onmaru.admin.pipeline;

import java.time.Instant;
import java.util.UUID;

public record AdminPipelineRunResult(
        String schemaVersion, UUID runId, String dataset, String scope, String status, Instant requestedAt) {
    public AdminPipelineRunResult(UUID runId, String status) {
        this("1.0", runId, "kto-korean-tour", "ALL", status, Instant.now());
    }
}

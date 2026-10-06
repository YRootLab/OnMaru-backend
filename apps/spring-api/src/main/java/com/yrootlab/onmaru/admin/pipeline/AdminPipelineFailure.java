package com.yrootlab.onmaru.admin.pipeline;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record AdminPipelineFailure(
        UUID id, @JsonIgnore UUID runId, Instant occurredAt, String endpoint, String contentId,
        String errorCode, String message, boolean retryable) {
}

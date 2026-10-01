package com.yrootlab.onmaru.admin.pipeline;

import java.time.Instant;

public record AdminPipelineStatus(String dataset, String status, Instant lastSuccessAt, long failureCount) {
}

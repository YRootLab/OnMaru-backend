package com.yrootlab.onmaru.admin.pipeline;

import java.util.UUID;

public record AdminPipelineRunResult(UUID runId, String status) {
}

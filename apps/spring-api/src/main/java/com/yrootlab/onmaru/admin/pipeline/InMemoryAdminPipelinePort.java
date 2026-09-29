package com.yrootlab.onmaru.admin.pipeline;

import java.util.UUID;

public final class InMemoryAdminPipelinePort implements AdminPipelinePort {
    @Override
    public AdminPipelineStatus status(String dataset) {
        return new AdminPipelineStatus(dataset, "MISSING", null, 0);
    }

    @Override
    public AdminPipelineRunResult run(String dataset) {
        return new AdminPipelineRunResult(UUID.randomUUID(), "QUEUED");
    }
}

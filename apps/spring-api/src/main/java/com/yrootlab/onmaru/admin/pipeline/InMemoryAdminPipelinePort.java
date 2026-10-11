package com.yrootlab.onmaru.admin.pipeline;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryAdminPipelinePort implements AdminPipelinePort {
    private final Map<UUID, AdminPipelineRun> runs = new ConcurrentHashMap<>();
    @Override
    public AdminPipelineStatus status(String dataset) {
        return new AdminPipelineStatus(dataset, "MISSING", null, 0);
    }

    @Override
    public AdminPipelineRunResult run(String dataset) {
        UUID runId = UUID.randomUUID();
        runs.put(runId, new AdminPipelineRun(runId, dataset, "ALL", "QUEUED", null, null, null, 0));
        return new AdminPipelineRunResult(runId, "QUEUED");
    }

    @Override public AdminPipelineRun run(String dataset, UUID runId) {
        var run = runs.get(runId);
        if (run == null || !run.dataset().equals(dataset)) throw new AdminPipelineRunNotFoundException();
        return run;
    }

    @Override public AdminPipelineFailurePage failures(String dataset, UUID runId, int limit, com.yrootlab.onmaru.catalog.application.pagination.AdminCursor cursor) {
        run(dataset, runId);
        return new AdminPipelineFailurePage(java.util.List.of(), 0, false, null);
    }
}

package com.yrootlab.onmaru.admin.pipeline;

import java.util.UUID;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;

public interface AdminPipelinePort {
    AdminPipelineStatus status(String dataset);
    AdminPipelineRunResult run(String dataset);
    default AdminPipelineRunResult run(String dataset, String scope) {
        if (!"ALL".equals(scope)) throw new IllegalArgumentException("unsupported scope");
        return run(dataset);
    }
    default AdminPipelineRun run(String dataset, UUID runId) {
        throw new AdminPipelineRunNotFoundException();
    }
    default AdminPipelineFailurePage failures(String dataset, UUID runId, int limit, AdminCursor cursor) {
        throw new AdminPipelineRunNotFoundException();
    }
}

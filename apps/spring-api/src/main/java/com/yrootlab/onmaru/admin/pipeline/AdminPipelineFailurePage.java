package com.yrootlab.onmaru.admin.pipeline;

import java.util.List;

public record AdminPipelineFailurePage(
        String schemaVersion, List<AdminPipelineFailure> items, long totalCount,
        boolean hasNext, String nextCursor) {
    public AdminPipelineFailurePage(List<AdminPipelineFailure> items, long totalCount, boolean hasNext, String nextCursor) {
        this("1.0", List.copyOf(items), totalCount, hasNext, nextCursor);
    }
}

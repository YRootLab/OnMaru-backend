package com.yrootlab.onmaru.admin.pipeline;

import com.yrootlab.onmaru.persistence.admin.JdbcAdminPipelinePort;
import com.yrootlab.onmaru.tourism.catalog.TourApiCatalogSyncService;

import java.util.UUID;

public final class TourApiAdminPipelinePort implements AdminPipelinePort {
    private static final String DATASET = "kto-korean-tour";
    private final JdbcAdminPipelinePort statusPort;
    private final TourApiCatalogSyncService syncService;

    public TourApiAdminPipelinePort(JdbcAdminPipelinePort statusPort, TourApiCatalogSyncService syncService) {
        this.statusPort = statusPort;
        this.syncService = syncService;
    }

    @Override
    public AdminPipelineStatus status(String dataset) {
        requireDataset(dataset);
        return statusPort.status(dataset);
    }

    @Override
    public AdminPipelineRunResult run(String dataset) {
        requireDataset(dataset);
        UUID runId = UUID.randomUUID();
        try {
            syncService.syncFullSnapshot();
            return new AdminPipelineRunResult(runId, "SUCCEEDED");
        } catch (RuntimeException exception) {
            return new AdminPipelineRunResult(runId, "FAILED");
        }
    }

    private void requireDataset(String dataset) {
        if (!DATASET.equals(dataset)) throw new IllegalArgumentException("unsupported dataset");
    }
}

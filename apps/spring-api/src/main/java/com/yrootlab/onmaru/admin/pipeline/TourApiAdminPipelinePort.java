package com.yrootlab.onmaru.admin.pipeline;

import com.yrootlab.onmaru.persistence.admin.JdbcAdminPipelinePort;
import com.yrootlab.onmaru.tourism.catalog.TourApiCatalogSyncService;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;

public final class TourApiAdminPipelinePort implements AdminPipelinePort {
    private static final String DATASET = "kto-korean-tour";
    private static final Executor PIPELINE_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "onmaru-admin-pipeline");
        thread.setDaemon(true);
        return thread;
    });

    private final Function<String, AdminPipelineStatus> statusReader;
    private final Runnable syncTask;
    private final Executor executor;
    private final Clock clock;
    private final Map<String, Execution> executions = new ConcurrentHashMap<>();

    public TourApiAdminPipelinePort(JdbcAdminPipelinePort statusPort, TourApiCatalogSyncService syncService) {
        this(statusPort, syncService::syncFullSnapshot, PIPELINE_EXECUTOR, Clock.systemUTC());
    }

    TourApiAdminPipelinePort(
            JdbcAdminPipelinePort statusPort,
            Runnable syncTask,
            Executor executor,
            Clock clock
    ) {
        this.statusReader = statusPort::status;
        this.syncTask = syncTask;
        this.executor = executor;
        this.clock = clock;
    }

    TourApiAdminPipelinePort(
            Function<String, AdminPipelineStatus> statusReader,
            Runnable syncTask,
            Executor executor,
            Clock clock
    ) {
        this.statusReader = statusReader;
        this.syncTask = syncTask;
        this.executor = executor;
        this.clock = clock;
    }

    @Override
    public AdminPipelineStatus status(String dataset) {
        requireDataset(dataset);
        var persisted = statusReader.apply(dataset);
        var execution = executions.get(dataset);
        if (execution == null) return persisted;

        var lastSuccessAt = execution.status().equals("SUCCEEDED")
                ? execution.finishedAt()
                : persisted.lastSuccessAt();
        var failureCount = persisted.failureCount() + (execution.status().equals("FAILED") ? 1 : 0);
        return new AdminPipelineStatus(dataset, execution.status(), lastSuccessAt, failureCount);
    }

    @Override
    public AdminPipelineRunResult run(String dataset) {
        requireDataset(dataset);
        UUID runId = UUID.randomUUID();
        var submitted = new boolean[1];
        var execution = executions.compute(dataset, (key, current) -> {
            if (current != null && current.isActive()) return current;
            submitted[0] = true;
            return new Execution(runId, "QUEUED", null);
        });

        if (submitted[0]) {
            CompletableFuture.runAsync(() -> execute(dataset, runId), executor);
        }
        return new AdminPipelineRunResult(execution.runId(), execution.status());
    }

    private void execute(String dataset, UUID runId) {
        executions.computeIfPresent(dataset, (key, current) -> current.runId().equals(runId)
                ? new Execution(runId, "RUNNING", null)
                : current);
        try {
            syncTask.run();
            executions.computeIfPresent(dataset, (key, current) -> current.runId().equals(runId)
                    ? new Execution(runId, "SUCCEEDED", clock.instant())
                    : current);
        } catch (RuntimeException exception) {
            executions.computeIfPresent(dataset, (key, current) -> current.runId().equals(runId)
                    ? new Execution(runId, "FAILED", clock.instant())
                    : current);
        }
    }

    private void requireDataset(String dataset) {
        if (!DATASET.equals(dataset)) throw new IllegalArgumentException("unsupported dataset");
    }

    private record Execution(UUID runId, String status, Instant finishedAt) {
        private boolean isActive() {
            return status.equals("QUEUED") || status.equals("RUNNING");
        }
    }
}

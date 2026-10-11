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
    private final AdminPipelinePort historyReader;
    private final Runnable syncTask;
    private final Executor executor;
    private final Clock clock;
    private final Map<String, Execution> executions = new ConcurrentHashMap<>();

    public TourApiAdminPipelinePort(JdbcAdminPipelinePort statusPort, TourApiCatalogSyncService syncService) {
        this(statusPort::status, statusPort, syncService::syncFullSnapshot, PIPELINE_EXECUTOR, Clock.systemUTC());
    }

    TourApiAdminPipelinePort(
            JdbcAdminPipelinePort statusPort,
            Runnable syncTask,
            Executor executor,
            Clock clock
    ) {
        this(statusPort::status, statusPort, syncTask, executor, clock);
    }

    TourApiAdminPipelinePort(
            Function<String, AdminPipelineStatus> statusReader,
            Runnable syncTask,
            Executor executor,
            Clock clock
    ) {
        this(statusReader, null, syncTask, executor, clock);
    }

    private TourApiAdminPipelinePort(
            Function<String, AdminPipelineStatus> statusReader,
            AdminPipelinePort historyReader,
            Runnable syncTask,
            Executor executor,
            Clock clock
    ) {
        this.statusReader = statusReader;
        this.historyReader = historyReader;
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
        long cumulativeFailures = persisted.cumulativeFailureRunCount()
                + (execution.status().equals("FAILED") ? 1 : 0);
        return AdminPipelineStatus.from(dataset, execution.status(), lastSuccessAt,
                cumulativeFailures, execution.toRun(dataset));
    }

    @Override
    public AdminPipelineRunResult run(String dataset) {
        requireDataset(dataset);
        UUID runId = UUID.randomUUID();
        var submitted = new boolean[1];
        var execution = executions.compute(dataset, (key, current) -> {
            if (current != null && current.isActive()) return current;
            submitted[0] = true;
            return new Execution(runId, "QUEUED", null, null);
        });

        if (submitted[0]) {
            CompletableFuture.runAsync(() -> execute(dataset, runId), executor);
        }
        return new AdminPipelineRunResult("1.0", execution.runId(), dataset, "ALL", execution.status(), clock.instant());
    }

    @Override
    public AdminPipelineRun run(String dataset, UUID runId) {
        requireDataset(dataset);
        var execution = executions.get(dataset);
        if (execution != null && execution.runId().equals(runId)) return execution.toRun(dataset);
        if (historyReader != null) return historyReader.run(dataset, runId);
        throw new AdminPipelineRunNotFoundException();
    }

    @Override
    public AdminPipelineFailurePage failures(String dataset, UUID runId, int limit, com.yrootlab.onmaru.catalog.application.pagination.AdminCursor cursor) {
        if (historyReader != null) return historyReader.failures(dataset, runId, limit, cursor);
        run(dataset, runId);
        return new AdminPipelineFailurePage(java.util.List.of(), 0, false, null);
    }

    private void execute(String dataset, UUID runId) {
        executions.computeIfPresent(dataset, (key, current) -> current.runId().equals(runId)
                ? new Execution(runId, "RUNNING", clock.instant(), null)
                : current);
        try {
            syncTask.run();
            executions.computeIfPresent(dataset, (key, current) -> current.runId().equals(runId)
                    ? new Execution(runId, "SUCCEEDED", current.startedAt(), clock.instant())
                    : current);
        } catch (RuntimeException exception) {
            executions.computeIfPresent(dataset, (key, current) -> current.runId().equals(runId)
                    ? new Execution(runId, "FAILED", current.startedAt(), clock.instant())
                    : current);
        }
    }

    private void requireDataset(String dataset) {
        if (!DATASET.equals(dataset)) throw new IllegalArgumentException("unsupported dataset");
    }

    private record Execution(UUID runId, String status, Instant startedAt, Instant finishedAt) {
        private boolean isActive() {
            return status.equals("QUEUED") || status.equals("RUNNING");
        }
        private AdminPipelineRun toRun(String dataset) {
            return new AdminPipelineRun(runId, dataset, "ALL", status, null, startedAt, finishedAt, 0);
        }
    }
}

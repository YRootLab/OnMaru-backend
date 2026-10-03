package com.yrootlab.onmaru.scheduling.audio;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;

/** Only internal codes and aggregate counts are persisted; never exception messages. */
public final class JdbcOdiiSyncRunStore implements OdiiSyncRunStore {
    private final DataSource dataSource;

    public JdbcOdiiSyncRunStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void save(Run run) {
        String status = switch (run.lifecycleStatus()) {
            case "STARTED" -> "RUNNING";
            case "COMPLETED" -> "SUCCEEDED";
            case "FAILED" -> "FAILED";
            case "SKIPPED" -> "ABANDONED";
            default -> throw new IllegalArgumentException("Unsupported scheduler lifecycle status");
        };
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO onmaru.operations_sync_runs
                    (id, dataset, scheduled_for, attempt, status, started_at, finished_at,
                     trigger_source, lifecycle_status, failure_phase, error_code, revision_id,
                     lease_generation, counts)
                VALUES (?, ?, ?, 1, ?::onmaru.operations_sync_run_status, ?, ?, ?, ?, ?, ?, ?, ?,
                        jsonb_build_object('fetched', ?::bigint, 'mapped', ?::bigint, 'staged', ?::bigint,
                                           'published', ?::bigint, 'tombstones', ?::bigint))
                ON CONFLICT (id) DO UPDATE SET
                    status = EXCLUDED.status, finished_at = EXCLUDED.finished_at,
                    lifecycle_status = EXCLUDED.lifecycle_status, failure_phase = EXCLUDED.failure_phase,
                    error_code = EXCLUDED.error_code, revision_id = EXCLUDED.revision_id,
                    lease_generation = EXCLUDED.lease_generation, counts = EXCLUDED.counts
                WHERE operations_sync_runs.lifecycle_status = 'STARTED'
                """)) {
            statement.setObject(1, run.id());
            statement.setString(2, run.dataset());
            statement.setTimestamp(3, Timestamp.from(run.startedAt()));
            statement.setString(4, status);
            statement.setTimestamp(5, Timestamp.from(run.startedAt()));
            statement.setTimestamp(6, run.finishedAt() == null ? null : Timestamp.from(run.finishedAt()));
            statement.setString(7, run.triggerSource());
            statement.setString(8, run.lifecycleStatus());
            statement.setString(9, run.failurePhase());
            statement.setString(10, run.errorCode());
            statement.setObject(11, run.revisionId());
            statement.setObject(12, run.leaseGeneration());
            statement.setLong(13, run.fetchedCount());
            statement.setLong(14, run.mappedCount());
            statement.setLong(15, run.stagedCount());
            statement.setLong(16, run.publishedCount());
            statement.setLong(17, run.tombstoneCount());
            statement.executeUpdate();
        } catch (SQLException exception) {
            // No original cause: driver messages can contain the full connection URL or provider data.
            throw new IllegalStateException("Odii run history persistence failed");
        }
    }
}

package com.yrootlab.onmaru.persistence.admin;

import com.yrootlab.onmaru.admin.pipeline.AdminPipelinePort;
import com.yrootlab.onmaru.admin.pipeline.AdminPipelineRunResult;
import com.yrootlab.onmaru.admin.pipeline.AdminPipelineStatus;
import com.yrootlab.onmaru.admin.pipeline.AdminPipelineRun;
import com.yrootlab.onmaru.admin.pipeline.AdminPipelineRunNotFoundException;
import com.yrootlab.onmaru.admin.pipeline.AdminPipelineFailure;
import com.yrootlab.onmaru.admin.pipeline.AdminPipelineFailurePage;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class JdbcAdminPipelinePort implements AdminPipelinePort {
    private final DataSource dataSource;

    public JdbcAdminPipelinePort(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public AdminPipelineStatus status(String dataset) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT max(finished_at) FILTER (WHERE status = 'SUCCEEDED') AS last_success_at,
                       count(*) FILTER (WHERE status = 'FAILED') AS cumulative_failure_count,
                       (array_agg(status::text ORDER BY COALESCE(started_at, scheduled_for) DESC))[1] AS latest_status
                FROM onmaru.operations_sync_runs WHERE dataset = ?
                """)) {
            statement.setString(1, dataset);
            try (var result = statement.executeQuery()) {
                result.next();
                String latest = result.getString("latest_status");
                var lastRun = latestRun(dataset);
                return AdminPipelineStatus.from(dataset, mapStatus(latest), optional(result, "last_success_at"),
                        result.getLong("cumulative_failure_count"), lastRun);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load pipeline status", exception);
        }
    }

    @Override
    public AdminPipelineRunResult run(String dataset) {
        throw new UnsupportedOperationException("pipeline run must use the dataset-specific sync adapter");
    }

    @Override
    public AdminPipelineRun run(String dataset, UUID runId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT id, dataset, COALESCE(scope, 'ALL') AS scope, status::text,
                       started_at, finished_at,
                       current_stage, progress_completed, progress_total,
                       (SELECT count(*) FROM onmaru.operations_sync_failures f WHERE f.run_id = r.id)
                         + CASE WHEN status IN ('FAILED', 'ABANDONED') AND error_code IS NOT NULL THEN 1 ELSE 0 END AS failure_count
                FROM onmaru.operations_sync_runs r WHERE dataset = ? AND id = ?
                """)) {
            statement.setString(1, dataset);
            statement.setObject(2, runId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new AdminPipelineRunNotFoundException();
                return mapRun(rows);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load pipeline run", exception);
        }
    }

    @Override
    public AdminPipelineFailurePage failures(String dataset, UUID runId, int limit, com.yrootlab.onmaru.catalog.application.pagination.AdminCursor cursor) {
        run(dataset, runId);
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                WITH failures AS (
                  SELECT id, run_id, occurred_at, endpoint, content_id, error_code, message, retryable
                  FROM onmaru.operations_sync_failures WHERE run_id = ?
                  UNION ALL
                  SELECT r.id, r.id, COALESCE(r.finished_at, r.scheduled_for), NULL, NULL,
                         COALESCE(r.error_code, 'PIPELINE_FAILED'), 'Pipeline run failed', true
                  FROM onmaru.operations_sync_runs r
                  WHERE r.id = ? AND r.status IN ('FAILED', 'ABANDONED') AND r.error_code IS NOT NULL
                )
                SELECT id, run_id, occurred_at, endpoint, content_id, error_code, message, retryable,
                       count(*) OVER () AS total_count
                FROM failures
                WHERE (?::timestamptz IS NULL OR (occurred_at, id) < (?::timestamptz, ?::uuid))
                ORDER BY occurred_at DESC, id DESC LIMIT ?
                """)) {
            statement.setObject(1, runId);
            statement.setObject(2, runId);
            if (cursor == null) {
                statement.setNull(3, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                statement.setNull(4, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                statement.setNull(5, java.sql.Types.OTHER);
            } else {
                statement.setObject(3, java.time.OffsetDateTime.ofInstant(cursor.timestamp(), java.time.ZoneOffset.UTC));
                statement.setObject(4, java.time.OffsetDateTime.ofInstant(cursor.timestamp(), java.time.ZoneOffset.UTC));
                statement.setObject(5, cursor.id());
            }
            statement.setInt(6, limit + 1);
            try (var rows = statement.executeQuery()) {
                var items = new java.util.ArrayList<AdminPipelineFailure>();
                long total = cursor != null && cursor.totalCount() != null ? cursor.totalCount() : 0;
                while (rows.next()) {
                    if (cursor == null) total = rows.getLong("total_count");
                    items.add(new AdminPipelineFailure(rows.getObject("id", UUID.class), runId,
                            optional(rows, "occurred_at"), rows.getString("endpoint"), rows.getString("content_id"),
                            rows.getString("error_code"), rows.getString("message"), rows.getBoolean("retryable")));
                }
                boolean hasNext = items.size() > limit;
                if (hasNext) items.remove(items.size() - 1);
                return new AdminPipelineFailurePage(items, total, hasNext, null);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load pipeline failures", exception);
        }
    }

    private AdminPipelineRun latestRun(String dataset) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT id, dataset, COALESCE(scope, 'ALL') AS scope, status::text,
                       started_at, finished_at,
                       current_stage, progress_completed, progress_total,
                       (SELECT count(*) FROM onmaru.operations_sync_failures f WHERE f.run_id = r.id)
                         + CASE WHEN status IN ('FAILED', 'ABANDONED') AND error_code IS NOT NULL THEN 1 ELSE 0 END AS failure_count
                FROM onmaru.operations_sync_runs r WHERE dataset = ?
                ORDER BY COALESCE(started_at, scheduled_for) DESC, id DESC LIMIT 1
                """)) {
            statement.setString(1, dataset);
            try (var rows = statement.executeQuery()) { return rows.next() ? mapRun(rows) : null; }
        }
    }

    private AdminPipelineRun mapRun(java.sql.ResultSet rows) throws SQLException {
        Long total = nullableLong(rows, "progress_total");
        Long completed = nullableLong(rows, "progress_completed");
        var progress = completed == null && total == null && rows.getString("current_stage") == null ? null
                : com.yrootlab.onmaru.admin.pipeline.AdminPipelineProgress.of(
                        completed == null ? 0 : completed, total, rows.getString("current_stage"));
        return new AdminPipelineRun(rows.getObject("id", UUID.class), rows.getString("dataset"), rows.getString("scope"),
                rows.getString("status"), progress, optional(rows, "started_at"),
                optional(rows, "finished_at"), rows.getLong("failure_count"));
    }

    private Long nullableLong(java.sql.ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private String mapStatus(String status) {
        if (status == null) return "MISSING";
        return switch (status) {
            case "QUEUED" -> "IDLE";
            case "RUNNING" -> "RUNNING";
            case "SUCCEEDED" -> "SUCCEEDED";
            case "FAILED", "ABANDONED" -> "FAILED";
            default -> "MISSING";
        };
    }

    private java.time.Instant optional(java.sql.ResultSet result, String column) throws SQLException {
        var value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

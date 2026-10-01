package com.yrootlab.onmaru.persistence.admin;

import com.yrootlab.onmaru.admin.pipeline.AdminPipelinePort;
import com.yrootlab.onmaru.admin.pipeline.AdminPipelineRunResult;
import com.yrootlab.onmaru.admin.pipeline.AdminPipelineStatus;

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
                       count(*) FILTER (WHERE status = 'FAILED') AS failure_count,
                       (array_agg(status::text ORDER BY COALESCE(started_at, scheduled_for) DESC))[1] AS latest_status
                FROM onmaru.operations_sync_runs WHERE dataset = ?
                """)) {
            statement.setString(1, dataset);
            try (var result = statement.executeQuery()) {
                result.next();
                String latest = result.getString("latest_status");
                return new AdminPipelineStatus(dataset, mapStatus(latest), optional(result, "last_success_at"), result.getLong("failure_count"));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load pipeline status", exception);
        }
    }

    @Override
    public AdminPipelineRunResult run(String dataset) {
        throw new UnsupportedOperationException("pipeline run must use the dataset-specific sync adapter");
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

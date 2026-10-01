package com.yrootlab.onmaru.persistence.admin;

import com.yrootlab.onmaru.admin.dashboard.AdminDashboardReadPort;
import com.yrootlab.onmaru.admin.dashboard.AdminDashboardService;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class JdbcAdminDashboardReadStore implements AdminDashboardReadPort {
    private final DataSource dataSource;

    public JdbcAdminDashboardReadStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public AdminDashboardMetrics metrics(Instant fromInclusive, Instant toExclusive) {
        String sql = """
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status='PUBLISHED') AS published,
                       count(*) FILTER (WHERE status='HIDDEN') AS hidden,
                       count(*) FILTER (WHERE status='REMOVED') AS removed
                FROM onmaru.community_visit_reviews
                WHERE created_at >= ? AND created_at < ? AND status <> 'DELETED'
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, OffsetDateTime.ofInstant(fromInclusive, ZoneOffset.UTC));
            statement.setObject(2, OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC));
            try (var result = statement.executeQuery()) {
                result.next();
                long pending;
                try (var report = connection.prepareStatement("SELECT count(*) FROM onmaru.community_review_reports WHERE status='OPEN' AND created_at >= ? AND created_at < ?")) {
                    report.setObject(1, OffsetDateTime.ofInstant(fromInclusive, ZoneOffset.UTC));
                    report.setObject(2, OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC));
                    try (var reportResult = report.executeQuery()) { reportResult.next(); pending = reportResult.getLong(1); }
                }
                return new AdminDashboardMetrics(result.getLong("total"), result.getLong("published"),
                        result.getLong("hidden"), result.getLong("removed"), pending);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load admin dashboard metrics", exception);
        }
    }

    @Override
    public List<AdminDashboardService.ReviewSummary> recentReviews(Instant fromInclusive, Instant toExclusive, int limit) {
        String sql = """
                SELECT id, status::text, text, created_at
                FROM onmaru.community_visit_reviews
                WHERE created_at >= ? AND created_at < ? AND status <> 'DELETED'
                ORDER BY created_at DESC, id DESC LIMIT ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, OffsetDateTime.ofInstant(fromInclusive, ZoneOffset.UTC));
            statement.setObject(2, OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC));
            statement.setInt(3, limit);
            try (var result = statement.executeQuery()) {
                var items = new ArrayList<AdminDashboardService.ReviewSummary>();
                while (result.next()) items.add(new AdminDashboardService.ReviewSummary(
                        result.getObject("id", UUID.class), result.getString("status"),
                        result.getString("text"), result.getObject("created_at", OffsetDateTime.class).toInstant()));
                return List.copyOf(items);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load recent admin reviews", exception);
        }
    }

    @Override
    public List<AdminDashboardService.ReportSummary> recentOpenReports(Instant fromInclusive, Instant toExclusive, int limit) {
        String sql = """
                SELECT id, review_id, reason, status::text, created_at
                FROM onmaru.community_review_reports
                WHERE status='OPEN' AND created_at >= ? AND created_at < ?
                ORDER BY created_at DESC, id DESC LIMIT ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, OffsetDateTime.ofInstant(fromInclusive, ZoneOffset.UTC));
            statement.setObject(2, OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC));
            statement.setInt(3, limit);
            try (var result = statement.executeQuery()) {
                var items = new ArrayList<AdminDashboardService.ReportSummary>();
                while (result.next()) items.add(new AdminDashboardService.ReportSummary(
                        result.getObject("id", UUID.class), result.getObject("review_id", UUID.class),
                        result.getString("reason"), result.getString("status"),
                        result.getObject("created_at", OffsetDateTime.class).toInstant()));
                return List.copyOf(items);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load recent admin reports", exception);
        }
    }
}

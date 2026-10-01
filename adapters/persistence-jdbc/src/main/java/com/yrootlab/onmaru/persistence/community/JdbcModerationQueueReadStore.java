package com.yrootlab.onmaru.persistence.community;

import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.catalog.application.pagination.AdminPage;
import com.yrootlab.onmaru.community.moderation.ModerationAction;
import com.yrootlab.onmaru.community.moderation.ModerationActorType;
import com.yrootlab.onmaru.community.moderation.ModerationQueueItem;
import com.yrootlab.onmaru.community.moderation.ModerationQueuePriority;
import com.yrootlab.onmaru.community.moderation.ModerationQueueReadStore;
import com.yrootlab.onmaru.community.moderation.ModerationReason;
import com.yrootlab.onmaru.community.moderation.ModerationQueueReport;
import com.yrootlab.onmaru.community.moderation.ReviewReport;
import com.yrootlab.onmaru.community.moderation.ReviewReportReason;
import com.yrootlab.onmaru.community.moderation.ReviewReportStatus;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.persistence.jdbc.JdbcTransactionRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** PostgreSQL keyset read model for the operator moderation queue. */
public final class JdbcModerationQueueReadStore implements ModerationQueueReadStore {
    private static final Duration HIGH_RISK_SLA = Duration.ofHours(24);
    private static final Duration STANDARD_SLA = Duration.ofHours(72);
    private static final String CANDIDATES_CTE = """
            WITH report_summary AS (
                SELECT review_id, min(created_at) AS first_report_at,
                       bool_or(reason = 'PERSONAL_DATA') AS has_personal_data
                FROM onmaru.community_review_reports
                WHERE status = 'OPEN'
                GROUP BY review_id
            ), pending_pii AS (
                SELECT action.review_id, action.created_at
                FROM onmaru.community_review_moderation_actions action
                WHERE action.actor_type = 'SYSTEM' AND action.reason = 'PII_HIGH_RISK'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM onmaru.community_review_moderation_actions newer
                      WHERE newer.review_id = action.review_id
                        AND (newer.created_at, newer.id) > (action.created_at, action.id)
                  )
            ), candidates AS (
                SELECT review.id AS review_id, review.text AS review_text, review.status AS review_status,
                       CASE WHEN COALESCE(report.has_personal_data, false)
                                 OR pending.review_id IS NOT NULL
                            THEN 0 ELSE 1 END AS priority_rank,
                       CASE WHEN report.first_report_at IS NULL THEN pending.created_at
                            WHEN pending.review_id IS NOT NULL
                                 THEN LEAST(report.first_report_at, pending.created_at)
                            ELSE report.first_report_at END AS oldest_signal_at
                FROM onmaru.community_visit_reviews review
                LEFT JOIN report_summary report ON report.review_id = review.id
                LEFT JOIN pending_pii pending ON pending.review_id = review.id
                WHERE review.public_place_id IS NOT NULL
                  AND (report.review_id IS NOT NULL OR pending.review_id IS NOT NULL)
            )
            """;

    private final JdbcTransactionRunner transactions;

    public JdbcModerationQueueReadStore(DataSource dataSource) {
        this(new JdbcTransactionRunner(dataSource));
    }

    public JdbcModerationQueueReadStore(JdbcTransactionRunner transactions) {
        this.transactions = transactions;
    }

    @Override
    public AdminPage<ModerationQueueItem> page(int limit, AdminCursor cursor, Instant now) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (cursor != null && !"HIGH_RISK".equals(cursor.sortGroup()) && !"STANDARD".equals(cursor.sortGroup())) {
            throw new IllegalArgumentException("moderation queue cursor has an invalid priority group");
        }
        return transactions.execute(connection -> page(connection, limit, cursor, now));
    }

    @Override
    public long oldestQueueAgeSeconds(Instant now) {
        return transactions.execute(connection -> {
            String sql = CANDIDATES_CTE + " SELECT COALESCE(MAX(GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (? - oldest_signal_at))))), 0)::bigint "
                    + "FROM candidates WHERE oldest_signal_at IS NOT NULL";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setObject(1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
                try (var result = statement.executeQuery()) {
                    if (!result.next()) return 0L;
                    return result.getLong(1);
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Moderation queue age query failed", exception);
            }
        });
    }

    private AdminPage<ModerationQueueItem> page(Connection connection, int limit, AdminCursor cursor, Instant now) {
        try {
            List<QueueKey> keys = queueKeys(connection, limit, cursor);
            boolean hasNext = keys.size() > limit;
            List<QueueKey> selected = keys.subList(0, Math.min(limit, keys.size()));
            if (selected.isEmpty()) return new AdminPage<>(List.of(), false);

            List<UUID> reviewIds = selected.stream().map(QueueKey::reviewId).toList();
            Map<UUID, List<ReviewReport>> reports = reports(connection, reviewIds);
            Map<UUID, List<ModerationAction>> actions = actions(connection, reviewIds);
            List<ModerationQueueItem> items = selected.stream().map(key -> {
                List<ReviewReport> queueReports = reports.getOrDefault(key.reviewId(), List.of());
                List<ModerationAction> priorActions = actions.getOrDefault(key.reviewId(), List.of());
                long ageSeconds = Math.max(0L, Duration.between(key.oldestSignalAt(), now).toSeconds());
                Duration sla = key.priority() == ModerationQueuePriority.HIGH_RISK ? HIGH_RISK_SLA : STANDARD_SLA;
                Instant target = key.oldestSignalAt().plus(sla);
                return new ModerationQueueItem(
                        key.reviewId(), key.reviewText(), key.reviewStatus(), key.priority(),
                        queueReports.stream().map(report -> new ModerationQueueReport(
                                report.reason(), report.detail(), report.createdAt())).toList(),
                        priorActions, key.oldestSignalAt(), ageSeconds, target, !target.isAfter(now));
            }).toList();
            return new AdminPage<>(items, hasNext);
        } catch (SQLException exception) {
            throw new IllegalStateException("Moderation queue page query failed", exception);
        }
    }

    private List<QueueKey> queueKeys(Connection connection, int limit, AdminCursor cursor) throws SQLException {
        StringBuilder sql = new StringBuilder(CANDIDATES_CTE)
                .append(" SELECT review_id, review_text, review_status, priority_rank, oldest_signal_at "
                        + "FROM candidates WHERE oldest_signal_at IS NOT NULL");
        if (cursor != null) sql.append(" AND (priority_rank, oldest_signal_at, review_id) > (?, ?, ?)");
        sql.append(" ORDER BY priority_rank, oldest_signal_at, review_id LIMIT ?");
        try (var statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            if (cursor != null) {
                statement.setInt(index++, "HIGH_RISK".equals(cursor.sortGroup()) ? 0 : 1);
                statement.setObject(index++, OffsetDateTime.ofInstant(cursor.timestamp(), ZoneOffset.UTC));
                statement.setObject(index++, cursor.id());
            }
            statement.setInt(index, limit + 1);
            try (var result = statement.executeQuery()) {
                List<QueueKey> keys = new ArrayList<>();
                while (result.next()) {
                    int rank = result.getInt("priority_rank");
                    keys.add(new QueueKey(
                            result.getObject("review_id", UUID.class), result.getString("review_text"),
                            VisitReviewStatus.valueOf(result.getString("review_status")),
                            rank == 0 ? ModerationQueuePriority.HIGH_RISK : ModerationQueuePriority.STANDARD,
                            result.getObject("oldest_signal_at", OffsetDateTime.class).toInstant()));
                }
                return keys;
            }
        }
    }

    private Map<UUID, List<ReviewReport>> reports(Connection connection, List<UUID> reviewIds) throws SQLException {
        String sql = "SELECT id, review_id, reporter_member_id, reason, detail, status, created_at "
                + "FROM onmaru.community_review_reports WHERE status='OPEN' AND review_id IN ("
                + placeholders(reviewIds.size()) + ") ORDER BY review_id, created_at, id";
        Map<UUID, List<ReviewReport>> grouped = new HashMap<>();
        try (var statement = connection.prepareStatement(sql)) {
            bindIds(statement, reviewIds);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    ReviewReport report = new ReviewReport(
                            result.getObject("id", UUID.class), result.getObject("review_id", UUID.class),
                            result.getObject("reporter_member_id", UUID.class),
                            ReviewReportReason.valueOf(result.getString("reason")), result.getString("detail"),
                            ReviewReportStatus.valueOf(result.getString("status")),
                            result.getObject("created_at", OffsetDateTime.class).toInstant());
                    grouped.computeIfAbsent(report.reviewId(), ignored -> new ArrayList<>()).add(report);
                }
            }
        }
        return grouped;
    }

    private Map<UUID, List<ModerationAction>> actions(Connection connection, List<UUID> reviewIds) throws SQLException {
        String sql = "SELECT id, review_id, actor_type, actor_ref, previous_status, next_status, reason, created_at "
                + "FROM onmaru.community_review_moderation_actions WHERE review_id IN ("
                + placeholders(reviewIds.size()) + ") ORDER BY review_id, created_at, id";
        Map<UUID, List<ModerationAction>> grouped = new HashMap<>();
        try (var statement = connection.prepareStatement(sql)) {
            bindIds(statement, reviewIds);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    ModerationAction action = new ModerationAction(
                            result.getObject("id", UUID.class), result.getObject("review_id", UUID.class),
                            ModerationActorType.valueOf(result.getString("actor_type")), result.getString("actor_ref"),
                            VisitReviewStatus.valueOf(result.getString("previous_status")),
                            VisitReviewStatus.valueOf(result.getString("next_status")),
                            ModerationReason.valueOf(result.getString("reason")),
                            result.getObject("created_at", OffsetDateTime.class).toInstant());
                    grouped.computeIfAbsent(action.reviewId(), ignored -> new ArrayList<>()).add(action);
                }
            }
        }
        return grouped;
    }

    private String placeholders(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(ignored -> "?").collect(Collectors.joining(","));
    }

    private void bindIds(java.sql.PreparedStatement statement, List<UUID> ids) throws SQLException {
        for (int index = 0; index < ids.size(); index++) statement.setObject(index + 1, ids.get(index));
    }

    private record QueueKey(UUID reviewId, String reviewText, VisitReviewStatus reviewStatus,
                            ModerationQueuePriority priority, Instant oldestSignalAt) {
    }
}

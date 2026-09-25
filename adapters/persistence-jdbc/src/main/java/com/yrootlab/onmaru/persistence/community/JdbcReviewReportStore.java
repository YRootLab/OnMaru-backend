package com.yrootlab.onmaru.persistence.community;

import com.yrootlab.onmaru.community.moderation.*;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.persistence.jdbc.JdbcTransactionRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** PostgreSQL report/audit store sharing one transaction with review mutations. */
public final class JdbcReviewReportStore implements ReviewReportStore {
    private final JdbcTransactionRunner transactions;

    public JdbcReviewReportStore(DataSource dataSource) {
        this(dataSource, new JdbcTransactionRunner(dataSource));
    }

    public JdbcReviewReportStore(DataSource dataSource, JdbcTransactionRunner transactions) {
        this.transactions = transactions;
    }

    @Override public <T> T executeAtomically(Supplier<T> operation) {
        return transactions.execute(ignored -> operation.get());
    }

    @Override public ReviewReport saveOrFindOpen(ReviewReport report) {
        return transactions.execute(connection -> saveOrFindOpen(connection, report));
    }

    @Override public List<ReviewReport> openReports() {
        return transactions.execute(connection -> reports(connection, "WHERE status='OPEN'"));
    }

    @Override public void closeOpenReports(UUID reviewId, ReviewReportStatus status) {
        if (status == ReviewReportStatus.OPEN) throw new IllegalArgumentException("closed report status is required");
        transactions.execute(connection -> {
            try (var statement = connection.prepareStatement("UPDATE onmaru.community_review_reports SET status=?::onmaru.community_report_status,resolved_at=now() WHERE review_id=? AND status='OPEN'")) {
                statement.setString(1, status.name()); statement.setObject(2, reviewId); statement.executeUpdate(); return null;
            } catch (Exception exception) { throw failure("Review report database operation failed", exception); }
        });
    }

    @Override public void addAudit(ModerationAction action) {
        transactions.execute(connection -> {
            try (var statement = connection.prepareStatement("INSERT INTO onmaru.community_review_moderation_actions (id,review_id,actor_type,actor_ref,previous_status,next_status,reason,created_at) VALUES (?,?,?,?,?::onmaru.community_review_status,?::onmaru.community_review_status,?,?)")) {
                statement.setObject(1, action.actionId()); statement.setObject(2, action.reviewId());
                statement.setString(3, action.actorType().name()); statement.setString(4, action.actorRef());
                statement.setString(5, action.previousStatus().name()); statement.setString(6, action.nextStatus().name());
                statement.setString(7, action.reason().name()); statement.setObject(8, OffsetDateTime.ofInstant(action.createdAt(), ZoneOffset.UTC));
                statement.executeUpdate(); return null;
            } catch (Exception exception) { throw failure("Moderation audit database operation failed", exception); }
        });
    }

    @Override public List<ModerationAction> auditLog() {
        return transactions.execute(connection -> {
            try (var statement = connection.prepareStatement("SELECT id,review_id,actor_type,actor_ref,previous_status,next_status,reason,created_at FROM onmaru.community_review_moderation_actions ORDER BY created_at,id"); var result = statement.executeQuery()) {
                var actions = new ArrayList<ModerationAction>();
                while (result.next()) actions.add(new ModerationAction(result.getObject("id", UUID.class), result.getObject("review_id", UUID.class), ModerationActorType.valueOf(result.getString("actor_type")), result.getString("actor_ref"), VisitReviewStatus.valueOf(result.getString("previous_status")), VisitReviewStatus.valueOf(result.getString("next_status")), ModerationReason.valueOf(result.getString("reason")), result.getObject("created_at", OffsetDateTime.class).toInstant()));
                return List.copyOf(actions);
            } catch (Exception exception) { throw failure("Moderation audit database operation failed", exception); }
        });
    }

    private ReviewReport saveOrFindOpen(Connection connection, ReviewReport report) {
        try (var statement = connection.prepareStatement("INSERT INTO onmaru.community_review_reports (id,review_id,reporter_member_id,reason,detail,status,created_at) VALUES (?,?,?,?,?,'OPEN',?) ON CONFLICT (review_id,reporter_member_id) WHERE status='OPEN' DO NOTHING")) {
            statement.setObject(1, report.reportId()); statement.setObject(2, report.reviewId()); statement.setObject(3, report.reporterMemberId());
            statement.setString(4, report.reason().name()); statement.setString(5, report.detail()); statement.setObject(6, OffsetDateTime.ofInstant(report.createdAt(), ZoneOffset.UTC));
            if (statement.executeUpdate() == 1) return report;
        } catch (Exception exception) { throw failure("Review report database operation failed", exception); }
        try (var query = connection.prepareStatement("SELECT id,reason,detail,created_at FROM onmaru.community_review_reports WHERE review_id=? AND reporter_member_id=? AND status='OPEN'")) {
            query.setObject(1, report.reviewId()); query.setObject(2, report.reporterMemberId());
            try (var result = query.executeQuery()) {
                if (result.next()) return new ReviewReport(result.getObject("id", UUID.class), report.reviewId(), report.reporterMemberId(), ReviewReportReason.valueOf(result.getString("reason")), result.getString("detail"), ReviewReportStatus.OPEN, result.getObject("created_at", OffsetDateTime.class).toInstant());
            }
        } catch (Exception exception) { throw failure("Review report database operation failed", exception); }
        throw new IllegalStateException("Open review report was not persisted");
    }

    private List<ReviewReport> reports(Connection connection, String where) {
        try (var statement = connection.prepareStatement("SELECT id,review_id,reporter_member_id,reason,detail,status,created_at FROM onmaru.community_review_reports " + where + " ORDER BY created_at,id"); var result = statement.executeQuery()) {
            var reports = new ArrayList<ReviewReport>();
            while (result.next()) reports.add(new ReviewReport(result.getObject("id", UUID.class), result.getObject("review_id", UUID.class), result.getObject("reporter_member_id", UUID.class), ReviewReportReason.valueOf(result.getString("reason")), result.getString("detail"), ReviewReportStatus.valueOf(result.getString("status")), result.getObject("created_at", OffsetDateTime.class).toInstant()));
            return List.copyOf(reports);
        } catch (Exception exception) { throw failure("Review report database operation failed", exception); }
    }

    private IllegalStateException failure(String message, Exception cause) { return new IllegalStateException(message, cause); }
}

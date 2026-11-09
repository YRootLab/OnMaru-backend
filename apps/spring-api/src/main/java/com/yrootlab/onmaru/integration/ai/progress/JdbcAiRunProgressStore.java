package com.yrootlab.onmaru.integration.ai.progress;

import com.yrootlab.onmaru.journey.run.JourneyRunStage;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.ApprovedPlanBinding;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.ApprovedBindingStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.yrootlab.onmaru.integration.ai.progress.AiRunProgressStore.Decision.*;

/**
 * PostgreSQL binding and ordinal store. record() locks the binding and its run together, so the ordinal
 * compare-and-set, active-run check, and effective stage decision commit or roll back as one unit.
 */
public final class JdbcAiRunProgressStore implements AiRunProgressStore, ApprovedBindingStore {

    private static final String UNIQUE_VIOLATION = "23505";
    private final DataSource dataSource;
    private final Clock clock;

    public JdbcAiRunProgressStore(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Override
    public void bind(ApprovedPlanBinding binding, Instant deadline) {
        var plan = binding.plan();
        Instant now = clock.instant();
        if (deadline == null || !deadline.isAfter(now)) {
            throw new IllegalArgumentException("future deadline is required to bind an approved plan");
        }
        try (Connection connection = dataSource.getConnection()) {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO onmaru.journey_run_ai_bindings (run_id, plan_hash, bound_at, expires_at, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (run_id) DO NOTHING
                    """)) {
                insert.setObject(1, plan.runId());
                insert.setString(2, plan.planHash());
                insert.setObject(3, utc(now));
                insert.setObject(4, utc(deadline));
                insert.setObject(5, utc(now));
                if (insert.executeUpdate() == 1) {
                    return;
                }
            }
            // A replayed bind is idempotent only for the same approved plan.
            if (!Optional.of(plan.planHash()).equals(boundPlanHash(plan.runId()))) {
                throw new IllegalStateException("run is already bound to a different approved plan");
            }
        } catch (SQLException exception) {
            if (UNIQUE_VIOLATION.equals(exception.getSQLState())) {
                throw new IllegalStateException("run is already bound", exception);
            }
            throw new IllegalStateException("approved plan binding could not be stored", exception);
        }
    }

    @Override
    public Optional<String> boundPlanHash(UUID runId) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT plan_hash FROM onmaru.journey_run_ai_bindings WHERE run_id = ?")) {
            statement.setObject(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("approved plan binding could not be read", exception);
        }
    }

    @Override
    public Result record(UUID runId, String planHash, int ordinal, JourneyRunStage stage) {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Result result = recordInTransaction(connection, runId, planHash, ordinal, stage);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("progress could not be recorded", exception);
        }
    }

    private Result recordInTransaction(Connection connection, UUID runId, String planHash, int ordinal,
                                       JourneyRunStage stage) throws SQLException {
        Instant now = clock.instant();
        final Row row;
        try (var select = connection.prepareStatement("""
                SELECT b.plan_hash, b.last_ordinal, b.last_stage, b.expires_at,
                       r.status::text AS run_status, r.deadline_at, r.stage AS run_stage
                FROM onmaru.journey_run_ai_bindings b
                JOIN onmaru.discovery_runs r ON r.id = b.run_id
                WHERE b.run_id = ?
                FOR UPDATE OF b, r
                """)) {
            select.setObject(1, runId);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    return new Result(NOT_BOUND, null);
                }
                row = new Row(
                        rows.getString("plan_hash"),
                        rows.getInt("last_ordinal"),
                        stageOrNull(rows.getString("last_stage")),
                        instant(rows, "expires_at"),
                        rows.getString("run_status"),
                        instant(rows, "deadline_at"),
                        stageOrNull(rows.getString("run_stage")));
            }
        }

        if (!row.planHash().equals(planHash)) {
            return new Result(PLAN_MISMATCH, null);
        }
        if (!"RUNNING".equals(row.runStatus()) || !row.deadlineAt().isAfter(now) || !row.expiresAt().isAfter(now)) {
            return new Result(INACTIVE, null);
        }
        if (ordinal < row.lastOrdinal()) {
            return new Result(STALE, null);
        }
        if (ordinal == row.lastOrdinal()) {
            return new Result(stage == row.lastStage() ? DUPLICATE : CONFLICT, null);
        }
        if (row.lastStage() != null && stage.ordinal() < row.lastStage().ordinal()) {
            return new Result(CONFLICT, null);
        }

        try (var update = connection.prepareStatement("""
                UPDATE onmaru.journey_run_ai_bindings
                SET last_ordinal = ?, last_stage = ?, updated_at = ?
                WHERE run_id = ? AND last_ordinal = ?
                """)) {
            update.setInt(1, ordinal);
            update.setString(2, stage.name());
            update.setObject(3, utc(now));
            update.setObject(4, runId);
            update.setInt(5, row.lastOrdinal());
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("progress ordinal changed during update");
            }
        }

        // Publish only when this callback moves the effective stage forward. The snapshot uses the same rule.
        JourneyRunStage before = max(row.runStage(), row.lastStage());
        boolean advanced = before == null || stage.ordinal() > before.ordinal();
        return new Result(APPLIED, advanced ? stage : null);
    }

    static JourneyRunStage max(JourneyRunStage left, JourneyRunStage right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private static JourneyRunStage stageOrNull(String value) {
        return value == null ? null : JourneyRunStage.valueOf(value);
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        return rows.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record Row(String planHash, int lastOrdinal, JourneyRunStage lastStage, Instant expiresAt,
                       String runStatus, Instant deadlineAt, JourneyRunStage runStage) { }
}
